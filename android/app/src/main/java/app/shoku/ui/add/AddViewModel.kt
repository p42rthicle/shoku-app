package app.shoku.ui.add

import app.shoku.domain.fridgeBatches
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Basis
import app.shoku.data.db.FoodKind
import app.shoku.data.db.Food
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.data.ai.AiClient
import app.shoku.data.ai.ResolvedItem
import app.shoku.data.net.BackendException
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.data.reference.toLibraryFood
import app.shoku.data.reference.ReferenceData
import app.shoku.data.reference.ReferenceFood
import app.shoku.domain.FoodFamilies
import app.shoku.domain.perUnit
import app.shoku.domain.suggestFamilies
import app.shoku.domain.Library
import app.shoku.domain.ParsedQuery
import app.shoku.domain.amountFor
import app.shoku.domain.matchScore
import app.shoku.domain.parseQuery
import app.shoku.domain.searchKey
import app.shoku.domain.searchText
import app.shoku.domain.Nutrients
import app.shoku.domain.batchConsumption
import app.shoku.domain.fractionLeft
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.shortLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Something that can be logged, with the amount to suggest. */
data class Suggestion(
    val refType: String,
    val refId: String,
    val name: String,
    val subtitle: String,
    val unit: String,
    val qty: Double,
    val nutrients: Nutrients?,
    /** Logged against a generic ("any brand"): typical values, with an optional brand note. */
    val generic: Boolean = false,
    /** Set for a dish from a bundled reference table; it's copied into the library when logged. */
    val reference: ReferenceFood? = null,
    /** A food that was just created (e.g. by scanning) and may not be in the library flow yet. */
    val directFood: Food? = null,
    /** Brand or variant chip. */
    val tag: String? = null,
    /** Portions of [directFood] (e.g. "serving" from Open Food Facts), saved with it. */
    val directPortions: List<app.shoku.data.db.FoodPortion> = emptyList(),
) {
    fun units(library: Library?): List<String> = when {
        reference != null -> reference.units
        directFood != null -> directPortions.sortedBy { it.qty }.map { it.label } + directFood.unit
        else -> library?.unitsFor(refType, refId).orEmpty().ifEmpty { listOf(unit) }
    }

    fun nutrients(library: Library?, unit: String, qty: Double): Nutrients? =
        reference?.nutrients(unit, qty)
            ?: directFood?.let { f ->
                when (unit) {
                    f.unit -> f.perUnit() * qty
                    else -> directPortions.firstOrNull { it.label == unit }?.let { p -> f.perUnit() * (p.qty * qty) }
                }
            }
            ?: library?.entryNutrients(refType, refId, unit, qty)
}

/** Open Food Facts search results for the current query. */
data class OnlineState(val query: String = "", val loading: Boolean = false, val items: List<Suggestion> = emptyList(), val failed: Boolean = false)

/** Where an AI request is at. */
sealed interface AiState {
    data object Idle : AiState
    data class Working(val what: String) : AiState
    /** Items to check before logging. */
    data class Review(val title: String, val items: List<ResolvedItem>) : AiState
    data class Failed(val message: String) : AiState
}

/** Where a barcode scan is at. */
sealed interface ScanState {
    data object Idle : ScanState
    data class Looking(val barcode: String) : ScanState
    /** A food that is ready to log: already in the library, or just saved from a scan. */
    data class Ready(val suggestion: Suggestion) : ScanState
    /** Not in the library yet: confirm or enter its values. [found] says whether Open Food Facts knew it. */
    data class NewFood(val draft: app.shoku.data.FoodDraft) : ScanState
    data class Failed(val message: String) : ScanState
}

/** A previous meal that can be copied in one tap. */
data class RepeatOption(val date: LocalDate, val meal: String, val entries: List<LogEntry>) {
    val kcal get() = entries.sumOf { it.kcal ?: 0.0 }
}

data class AddState(
    val usual: List<Suggestion> = emptyList(),
    val fridge: List<Suggestion> = emptyList(),
    val results: List<Suggestion> = emptyList(),
    val references: List<Suggestion> = emptyList(),
    val repeat: RepeatOption? = null,
    /** Older days' versions of this meal (up to 5 more), behind "Copy an older …". */
    val olderRepeats: List<RepeatOption> = emptyList(),
    val library: Library? = null,
    /** What was typed, split into food words and an amount. */
    val parsed: ParsedQuery? = null,
)

/** Usage of one item in one meal: how often, how recently, and the amount usually logged. */
private data class Usage(val score: Double, val unit: String, val qty: Double)

private typealias Key = Pair<String, String>

/**
 * Everything derived from the library and the full log, built once per change to either.
 * Typing in the search box then only filters this; it never rescans the log.
 */
private class AddIndex(
    val library: Library,
    val all: List<LogEntry>,
    val usageAll: Map<Key, Usage>,
    val usageByMeal: Map<String, Map<Key, Usage>>,
    val activeBatches: List<String>,
    val batchLeft: Map<String, String>,
    /** Per food: searchKey'd name with brand, its own aliases, and its family's name and aliases. */
    val foodKeys: Map<String, Triple<String, String, String>>,
)

@OptIn(FlowPreview::class)
class AddViewModel(
    private val repo: ShokuRepository,
    private val reference: ReferenceData,
    val date: LocalDate,
    initialMeal: String,
) : ViewModel() {

    val scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val ai = MutableStateFlow<AiState>(AiState.Idle)
    private val aiClient = AiClient()

    val meal = MutableStateFlow(initialMeal)
    val query = MutableStateFlow("")
    /** Each add or copy is one group, so undo removes a whole copied meal at once. */
    val added = MutableStateFlow<List<List<LogEntry>>>(emptyList())

    private val index = combine(repo.library, repo.allEntries) { library, all -> buildIndex(library, all) }.flowOn(Dispatchers.Default)

    val state: StateFlow<AddState> = combine(index, meal, query.debounce { if (it.isBlank()) 0L else app.shoku.ui.components.SEARCH_DEBOUNCE_MS }) { idx, m, q ->
        build(idx, m, q.trim())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddState())

    private fun buildIndex(library: Library, all: List<LogEntry>): AddIndex {
        val today = LocalDate.now()
        val ageByDate = HashMap<String, Long>()
        fun weight(e: LogEntry): Double {
            val age = ageByDate.getOrPut(e.date) {
                runCatching { ChronoUnit.DAYS.between(LocalDate.parse(e.date), today) }.getOrDefault(365L).coerceAtLeast(0)
            }
            // Recent habits count far more than old ones: a week ago ~0.7, a month ~0.1, four months ~0.007.
            // (1 / (1 + age/30) let a summer of daily müsli outrank this week's meals.)
            return 1.0 / (1.0 + (age / 10.0) * (age / 10.0))
        }
        // Entries of archived size rows ("Banana (m)") count towards their generic with the matching portion.
        fun canonical(e: LogEntry): LogEntry {
            if (e.refType != RefType.FOOD || e.refId == null) return e
            val f = library.foods[e.refId] ?: return e
            if (!f.archived || f.parentId == null) return e
            val label = FoodFamilies.sizeLabel(f.name)?.takeIf { library.portion(f.parentId, it) != null } ?: return e
            return e.copy(refId = f.parentId, unit = label)
        }
        fun usage(entries: List<LogEntry>): Map<Key, Usage> = entries
            .filter { it.refId != null }
            .map(::canonical)
            .groupBy { it.refType to it.refId!! }
            .mapValues { (_, list) ->
                val (unit, qty) = list.groupingBy { it.unit to it.qty }.eachCount().maxByOrNull { it.value }!!.key
                Usage(list.sumOf(::weight), unit, qty)
            }

        val batchLogs = all.filter { it.refType == RefType.BATCH }
        val left = HashMap<String, String>()
        val active = library.batches.values
            .filter { !it.finished && !it.deleted && it.date <= date.toString() }
            .filter { b ->
                val m = library.batchMetrics(b)
                val c = batchConsumption(m, b.id, batchLogs)
                left[b.id] = c.remainingWeight?.let { "${formatQty(it)} ${m.finalUnit} left" } ?: "${formatUnitQty(c.remainingServings, "serving")} left"
                c.fractionLeft(m) > 0.02
            }
            .sortedByDescending { it.date }
            .map { it.id }
        val foodKeys = library.foods.values.filter { !it.archived && !it.deleted }.associate { f ->
            val parent = f.parentId?.let { library.foods[it] }
            f.id to Triple(searchKey(listOfNotNull(f.name, f.brand).joinToString(" ")), searchKey(f.aliases), searchKey(listOfNotNull(parent?.name, parent?.aliases).joinToString(" ")))
        }
        return AddIndex(library, all, usage(all), all.groupBy { it.meal }.mapValues { usage(it.value) }, active, left, foodKeys)
    }

    private fun build(idx: AddIndex, meal: String, q: String): AddState {
        val library = idx.library
        val usageMeal = idx.usageByMeal[meal].orEmpty()

        fun suggestion(refType: String, refId: String, usage: Usage?): Suggestion? {
            val name = library.nameOf(refType, refId) ?: return null
            val units = library.unitsFor(refType, refId)
            if (units.isEmpty()) return null
            val unit = usage?.unit?.takeIf { it in units } ?: library.defaultUnit(refType, refId)?.takeIf { it in units } ?: units.first()
            val qty = usage?.qty?.takeIf { usage.unit == unit } ?: defaultQty(library, refType, refId, unit)
            return Suggestion(
                refType, refId, name, subtitle(idx, refType, refId), unit, qty, library.entryNutrients(refType, refId, unit, qty),
                generic = refType == RefType.FOOD && library.foods[refId]?.kind == FoodKind.GENERIC,
                tag = if (refType == RefType.FOOD) library.foods[refId]?.brand else null,
            )
        }

        val fridge = idx.activeBatches.mapNotNull { suggestion(RefType.BATCH, it, idx.usageAll[RefType.BATCH to it]) }

        if (q.isEmpty()) {
            val usual = usageMeal.entries
                .filter { (key, _) -> key.first != RefType.BATCH }
                .sortedByDescending { it.value.score }
                .asSequence()
                .mapNotNull { (key, u) -> suggestion(key.first, key.second, u) }
                .take(10)
                .toList()
            val repeats = repeatOptions(idx.all, meal)
            return AddState(usual = usual, fridge = fridge, repeat = repeats.firstOrNull(), olderRepeats = repeats.drop(1), library = library)
        }

        // Hybrid ranking: how well the words match, plus how often you log it, plus whether it has the
        // unit you typed. Your own foods come first; the databases and online products come after.
        val parsed = parseQuery(q)
        val text = searchKey(parsed.text).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val scored = ArrayList<Pair<Key, Double>>()
        fun consider(key: Key, match: Int) {
            if (match <= 0) return
            val usage = idx.usageAll[key]?.score ?: 0.0
            val units = library.unitsFor(key.first, key.second)
            val unitBonus = if (parsed.unit != null && amountFor(units, parsed) != null) 15.0 else 0.0
            val kindBonus = when {
                key.first == RefType.BATCH -> 10.0
                key.first == RefType.FOOD && library.foods[key.second]?.kind == FoodKind.GENERIC -> 4.0
                else -> 0.0
            }
            scored += key to (match + minOf(usage * 6.0, 40.0) + unitBonus + kindBonus)
        }
        if (text.isNotEmpty()) {
            idx.foodKeys.forEach { (id, k) -> consider(RefType.FOOD to id, matchScore(text, k.first, k.second, k.third)) }
            library.dishes.values.filter { !it.archived && !it.deleted }.forEach { consider(RefType.DISH to it.id, matchScore(text, searchKey(it.name), "")) }
            idx.activeBatches.forEach { consider(RefType.BATCH to it, matchScore(text, searchKey(library.batches[it]!!.dishName), "")) }
        }
        val results = scored
            .sortedWith(compareByDescending<Pair<Key, Double>> { it.second }.thenBy { library.nameOf(it.first.first, it.first.second) })
            .take(40)
            .mapNotNull { (key, _) ->
                val s = suggestion(key.first, key.second, usageMeal[key] ?: idx.usageAll[key]) ?: return@mapNotNull null
                // The amount typed ("4 tbsp") replaces the usual amount when the item has that unit.
                amountFor(s.units(library), parsed)?.let { (u, qty) -> s.copy(unit = u, qty = qty, nutrients = library.entryNutrients(key.first, key.second, u, qty)) } ?: s
            }
        // Reference dishes not yet in the library (once logged they show up as library foods).
        val refs = (if (text.isNotEmpty()) reference.search(text) else emptyList()).filter { !library.hasLive(it.libraryId) }.map { r ->
            val typed = amountFor(r.units, parsed)
            // Grams first; the database's serving ("1 slice = 287 g") stays a unit to pick, not the default.
            val unit = typed?.first ?: "g"
            val qty = typed?.second ?: 100.0
            val per = if (r.servingLabel != null && r.servingGrams != null) "1 ${r.servingLabel} ≈ ${formatQty(r.servingGrams)} g · " else ""
            val from = if (r.source == "indb") "Indian dish" else "German food database"
            Suggestion(
                "reference", r.code, r.name, "$from · $per${formatQty(Math.round(r.per100.kcal ?: 0.0).toDouble())} kcal/100 g",
                unit, qty, r.nutrients(unit, qty), generic = true, reference = r,
            )
        }
        return AddState(results = results, references = refs, fridge = fridge, library = library, parsed = parsed)
    }

    /** Foods default to their base amount (100 g, 1 pc); dishes and batches to one serving's worth. */
    private fun defaultQty(library: Library, refType: String, refId: String, unit: String): Double {
        if (refType == RefType.FOOD) {
            val f = library.foods[refId] ?: return 1.0
            return if (unit != f.unit) 1.0 else f.baseQty.takeIf { it > 0 } ?: 1.0 // a named portion starts at 1
        }
        if (unit == "serving") return 1.0
        val metrics = when (refType) {
            RefType.DISH -> library.dishes[refId]?.let { library.dishMetrics(it) }
            RefType.BATCH -> library.batches[refId]?.let { library.batchMetrics(it) }
            else -> null
        }
        return metrics?.gramsPerServing?.let { kotlin.math.round(it) } ?: 1.0
    }

    private fun subtitle(idx: AddIndex, refType: String, refId: String): String {
        val library = idx.library
        return when (refType) {
            RefType.FOOD -> library.foods[refId]?.let { f ->
                val kcal = f.kcal?.let { " · ${formatQty(Math.round(it).toDouble())} kcal" } ?: ""
                // The brand shows as a chip, so the line underneath is just the numbers.
                if (f.kind == FoodKind.GENERIC) "Any brand · per ${formatUnitQty(f.baseQty, f.unit)}$kcal"
                else "per ${formatUnitQty(f.baseQty, f.unit)}$kcal"
            } ?: ""
            RefType.DISH -> library.dishes[refId]?.let { d -> if (d.fixed) "Dish · fixed" else "Dish · ${formatUnitQty(d.servings, "serving")}" } ?: ""
            RefType.BATCH -> library.batches[refId]?.let { b -> "Batch · ${LocalDate.parse(b.date).dayMonth()} · ${idx.batchLeft[refId].orEmpty()}" } ?: ""
            else -> ""
        }
    }

    /** The most recent earlier day that had entries in this meal. */
    /** The last 6 earlier days that had this meal, newest first. */
    private fun repeatOptions(all: List<LogEntry>, meal: String): List<RepeatOption> {
        val before = all.filter { it.meal == meal && it.date < date.toString() }
        return before.map { it.date }.distinct().sortedDescending().take(6).map { d ->
            RepeatOption(LocalDate.parse(d), meal, before.filter { it.date == d }.sortedBy { it.createdAt })
        }
    }

    private fun repeatOption(all: List<LogEntry>, meal: String): RepeatOption? {
        val before = all.filter { it.meal == meal && it.date < date.toString() }
        val last = before.maxOfOrNull { it.date } ?: return null
        return RepeatOption(LocalDate.parse(last), meal, before.filter { it.date == last }.sortedBy { it.createdAt })
    }

    fun repeatLabel(option: RepeatOption): String = when (option.date) {
        date.minusDays(1) -> "Yesterday's ${option.meal.lowercase()}"
        else -> "${option.meal} from ${option.date.shortLabel()}"
    }

    // ---------- Online products (Open Food Facts) ----------

    val online = MutableStateFlow(OnlineState())
    private val onlineCache = HashMap<String, List<Suggestion>>()

    init {
        // Free, but rate-limited by Open Food Facts: only after typing pauses, and each query once.
        viewModelScope.launch {
            query.debounce(600).collectLatest { raw ->
                val text = parseQuery(raw.trim()).text.trim()
                if (text.length < 3) { online.value = OnlineState(); return@collectLatest }
                onlineCache[text]?.let { online.value = OnlineState(text, items = it); return@collectLatest }
                online.value = OnlineState(text, loading = true)
                runCatching { OpenFoodFacts.search(text) }
                    .onSuccess { products ->
                        val lib = currentLibrary()
                        val known = lib.foods.values.mapNotNull { it.barcode }.toSet()
                        val items = products.filter { it.barcode !in known }.map { onlineSuggestion(it, parseQuery(raw.trim())) }
                        onlineCache[text] = items
                        online.value = OnlineState(text, items = items)
                    }
                    .onFailure { online.value = OnlineState(text, failed = true) }
            }
        }
    }

    private fun onlineSuggestion(p: app.shoku.data.reference.ScannedProduct, parsed: ParsedQuery): Suggestion {
        val (food, portions) = p.toLibraryFood()
        val id = food.id
        val base = Suggestion(
            RefType.FOOD, id, food.name, "${if (app.shoku.data.reference.OfflineProducts.lookup(p.barcode) != null) "Built in" else "Online"} · per 100 ${p.unit} · ${p.kcal?.let { Math.round(it) } ?: "?"} kcal",
            p.unit, 100.0, food.perUnit() * 100.0, tag = p.brand, directFood = food, directPortions = portions,
        )
        val typed = amountFor(base.units(null), parsed) ?: (portions.firstOrNull { it.label != "pack" }?.let { it.label to 1.0 })
        return typed?.let { (u, q) -> base.copy(unit = u, qty = q, nutrients = base.nutrients(null, u, q)) } ?: base
    }

    fun add(s: Suggestion, unit: String, qty: Double, status: String, variant: String? = null, note: String? = null) = viewModelScope.launch {
        val library = state.value.library
        val n = s.nutrients(library, unit, qty)
        // An online product is saved to the library (with its portions) the first time it's logged.
        if (s.directFood != null && library?.foods?.containsKey(s.directFood.id) != true) {
            repo.saveFood(s.directFood, updatePast = false)
            if (s.directPortions.isNotEmpty()) repo.savePortions(s.directPortions)
        }
        // A reference dish becomes a generic food in the library the first time it's logged.
        val (refType, refId) = if (s.reference != null) RefType.FOOD to repo.adoptReference(s.reference) else s.refType to s.refId
        val entry = LogEntry(
            id = ShokuRepository.newId(), date = date.toString(), meal = meal.value,
            refType = refType, refId = refId, name = s.name, unit = unit, qty = qty,
            kcal = n?.kcal, protein = n?.protein, fibre = n?.fibre, carbs = n?.carbs, fat = n?.fat,
            status = status,
            basis = if (s.generic) Basis.TYPICAL else Basis.EXACT,
            variant = variant,
            note = note,
        )
        repo.add(entry)
        added.value = added.value + listOf(listOf(entry))
    }

    fun copy(option: RepeatOption, status: String, only: Set<String>? = null) = viewModelScope.launch {
        val copies = repo.copyMeal(option.date, option.meal, date, meal.value, status, only)
        if (copies.isNotEmpty()) added.value = added.value + listOf(copies)
    }

    // ---------- Barcode scanning ----------

    fun onBarcode(code: String) = viewModelScope.launch {
        scan.value = ScanState.Looking(code)
        val library = state.value.library ?: repo.library.first()
        scan.value = when (val r = app.shoku.data.FoodLookup.barcode(repo, code)) {
            is app.shoku.data.BarcodeResult.Known -> ScanState.Ready(foodSuggestion(r.food, library))
            is app.shoku.data.BarcodeResult.New -> ScanState.NewFood(r.draft)
            app.shoku.data.BarcodeResult.Offline -> ScanState.Failed("Couldn't reach Open Food Facts. Check the connection and scan again.")
        }
    }

    /** A food typed by hand (from "+ New food"), then logged like a scanned one. */
    fun newFood(name: String) = viewModelScope.launch {
        scan.value = ScanState.NewFood(app.shoku.data.FoodLookup.manual(currentLibrary(), name))
    }

    /** Saves a scanned or label-read food with its units; the amount sheet then starts on the first unit (1 slice). */
    fun saveScanned(food: Food, portions: List<app.shoku.data.db.FoodPortion> = emptyList()) = viewModelScope.launch {
        repo.saveFood(food, updatePast = false)
        val live = portions.filter { !it.deleted && it.foodId == food.id }
        if (portions.isNotEmpty()) repo.savePortions(portions)
        val s = foodSuggestion(food, state.value.library).copy(directPortions = live)
        val first = live.minByOrNull { it.qty }
        scan.value = ScanState.Ready(if (first != null) s.copy(unit = first.label, qty = 1.0, nutrients = s.nutrients(null, first.label, 1.0)) else s)
    }

    fun clearScan() { scan.value = ScanState.Idle }

    private val latestLogs = repo.allEntries.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /**
     * Picking a food-database or online product you already have under your own name ("Protein Wraps"
     * online vs your "High-protein wrap"): your version, if you've logged it or it has the same barcode.
     */
    fun ownVersion(s: Suggestion): Pair<Suggestion, Int>? {
        val lib = state.value.library ?: return null
        val cand = s.directFood?.takeIf { !lib.foods.containsKey(it.id) } ?: s.reference?.toFood() ?: return null
        val logs = latestLogs.value
        val uses = logs.filter { !it.deleted && it.refType == RefType.FOOD }.groupingBy { it.refId }.eachCount()
        val f = app.shoku.domain.Duplicates.matches(lib, cand, logs).firstOrNull { f ->
            !f.archived && ((uses[f.id] ?: 0) > 0 || (!cand.barcode.isNullOrBlank() && f.barcode == cand.barcode))
        } ?: return null
        return foodSuggestion(f, lib) to (uses[f.id] ?: 0)
    }

    /** "Already in your library": log that food instead (the scanned barcode is added to it). */
    fun useExisting(food: Food, draft: app.shoku.data.FoodDraft) = viewModelScope.launch {
        val f = app.shoku.data.FoodLookup.useExisting(repo, food, draft)
        val lib = repo.library.first { it.foods[f.id]?.barcode == f.barcode }
        scan.value = ScanState.Ready(foodSuggestion(f, lib))
    }

    // ---------- AI ----------

    private suspend fun currentLibrary(): Library = state.value.library ?: repo.library.first()

    // The running AI request, so the loading overlay's Cancel can stop it.
    private var aiJob: kotlinx.coroutines.Job? = null
    // Off the main thread from the first line, so the loader shows at once and the screen never waits on
    // the library and database lookups; a tap while a request runs is ignored (no double requests).
    private fun aiLaunch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): kotlinx.coroutines.Job? {
        if (aiJob?.isActive == true) return aiJob
        return viewModelScope.launch(Dispatchers.Default, block = block).also { aiJob = it }
    }

    fun cancelAi() {
        aiJob?.cancel()
        ai.value = AiState.Idle
        pendingPhotos = emptyList()
    }

    /** What Shoku remembers that fits this request: [query] (typed or noted), this meal, the fridge. */
    private fun memoryHints(lib: Library, query: String?): List<String> {
        val logs = latestLogs.value
        val batchLogs = logs.filter { it.refType == RefType.BATCH && !it.deleted }
        // Batches in the fridge, with what's left: usually what's on the plate.
        val fridge = lib.fridgeBatches(date, logs).map { b ->
            val m = lib.batchMetrics(b)
            val left = batchConsumption(m, b.id, batchLogs).fractionLeft(m)
            val fq = b.finalQty
            b.dishName + if (fq != null && b.finalUnit != null) " (${Math.round(left * fq)} ${b.finalUnit} left)" else ""
        }
        return app.shoku.data.ai.AiMemory.hints(logs, query, meal.value, fridge, date)
    }

    /** The plate photos of the review being shown, saved with the entries when they're logged. */
    private var pendingPhotos: List<String> = emptyList()
    /** What the AI proposed in that review, to learn from what was changed before logging. */
    private var proposed: List<ResolvedItem> = emptyList()
    /** Photos: where it was eaten (the review's At home / Eaten out) and the dishware the AI saw. */
    val place = MutableStateFlow<String?>(null)
    private var dishware: String? = null

    private fun aiFailure(e: Throwable): AiState = if (e is kotlinx.coroutines.CancellationException) AiState.Idle else AiState.Failed(
        when (e) {
            is BackendException -> e.message ?: "AI request failed"
            is java.net.SocketTimeoutException -> "AI took too long. Try again."
            is java.io.IOException -> "No connection to the server."
            else -> e.message ?: "Something went wrong"
        },
    )

    /** "2 eggs, toast with butter and a coffee" -> items to review. */
    fun aiDescribe(text: String) = aiLaunch {
        ai.value = AiState.Working("Working out what you ate…")
        runCatching {
            val lib = currentLibrary()
            val keys = aiClient.libraryKeys(lib, extra = aiClient.referenceCandidates(text, reference, lib))
            place.value = null
            val result = aiClient.meal(text, keys, memoryHints(lib, text))
            AiState.Review("Check before logging", aiClient.resolve(result.items, keys, lib, byEye = false).also { proposed = it; pendingPhotos = emptyList() })
        }.onSuccess { ai.value = it }.onFailure { ai.value = aiFailure(it) }
    }

    /** One to four photos of a plate (plus an optional note) -> estimated items to review. */
    fun aiPlate(photos: List<String>, note: String?) = aiLaunch {
        ai.value = AiState.Working(if (photos.size > 1) "Looking at your ${photos.size} photos…" else "Looking at your plate…")
        runCatching {
            val lib = currentLibrary()
            val keys = aiClient.libraryKeys(lib, extra = note?.let { aiClient.referenceCandidates(it, reference, lib) }.orEmpty())
            val refs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.shoku.data.PhotoStore.homeRefsBase64() }
            val result = aiClient.plate(photos, note?.takeIf { it.isNotBlank() }, keys, memoryHints(lib, note), refs)
            AiState.Review("Estimated from the photo", aiClient.resolve(result.items, keys, lib, byEye = true).also { items ->
                proposed = items
                pendingPhotos = photos
                dishware = result.dishware?.trim()?.takeIf { it.isNotEmpty() }
                // A fridge batch on the plate means home; otherwise what the AI saw (unclear counts as home).
                place.value = if (items.any { it.refType == RefType.BATCH } || result.setting != "outside") "home" else "outside"
            })
        }.onSuccess { ai.value = it }.onFailure { ai.value = aiFailure(it) }
    }

    /** What the barcode found, kept while a label or pack photo is read, then merged with it (barcode first). */
    private var photoBase: app.shoku.data.FoodDraft? = null
    fun keepForPhoto(d: app.shoku.data.FoodDraft) {
        photoBase = d.takeIf { app.shoku.data.DraftMerge.worthKeeping(it) }?.let { it.merged?.let { (a, _) -> it.copy(prefill = a, merged = null, choices = emptyList()) } ?: it }
    }
    private fun combine(d: app.shoku.data.FoodDraft) = (photoBase?.let { app.shoku.data.DraftMerge.merge(it, d) } ?: d).also { photoBase = null }
    /** Barcode or photo picked for a field they disagree on. */
    fun showDraft(d: app.shoku.data.FoodDraft) { scan.value = ScanState.NewFood(d) }

    /** A photo of a nutrition label -> a new food to confirm (keeps a scanned barcode if there was one). */
    fun aiLabel(jpegBase64: String, barcode: String? = null) = aiLaunch {
        ai.value = AiState.Working("Reading the label…")
        // No nutrition table in the photo: it still works out what the product is.
        runCatching { app.shoku.data.FoodLookup.labelOrProduct(aiClient, currentLibrary(), jpegBase64, barcode) }
            .onSuccess { draft ->
                if (draft == null) {
                    ai.value = AiState.Failed("Couldn't read a label or tell what that is. Try the front of the pack, with the name in focus.")
                    return@onSuccess
                }
                ai.value = AiState.Idle
                scan.value = ScanState.NewFood(combine(draft))
            }
            .onFailure { ai.value = aiFailure(it) }
    }

    /** A photo of any side of a pack (no label needed) -> a new food: brand and name, typical values if none printed. */
    fun aiPack(jpegsBase64: List<String>, barcode: String? = null) = aiLaunch {
        ai.value = AiState.Working("Working out what this is…")
        runCatching { app.shoku.data.FoodLookup.product(aiClient, currentLibrary(), jpegsBase64, barcode) }
            .onSuccess { draft ->
                if (draft == null) { ai.value = AiState.Failed("Couldn't tell what that is. Try the front of the pack, with the name in focus."); return@onSuccess }
                ai.value = AiState.Idle
                scan.value = ScanState.NewFood(combine(draft))
            }
            .onFailure { ai.value = aiFailure(it) }
    }

    fun clearAi() { ai.value = AiState.Idle; pendingPhotos = emptyList(); place.value = null }

    /** Logs the reviewed items as one group, so a single undo removes them all. */
    fun logAi(items: List<ResolvedItem>, status: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        // Learn from what was changed in the review; keep the plate photos with what was logged.
        app.shoku.data.ai.AiMemory.learnFromReview(proposed, items, meal.value)
        val where = place.value
        // Photos are kept with the meal when "Save photo with the meal" is on; a meal at home also keeps its first
        // photo as a reference for that kind of dishware (even with saving off).
        val keepWithMeal = app.shoku.data.PhotoStore.saveWithMeal
        val saved = if (pendingPhotos.isEmpty() || (!keepWithMeal && where != "home")) emptyList()
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (if (keepWithMeal) pendingPhotos else pendingPhotos.take(1)).map { app.shoku.data.PhotoStore.save(it) } }
        val photoNames = if (keepWithMeal) saved.joinToString(",").ifEmpty { null } else null
        if (where == "home") dishware?.let { d ->
            app.shoku.data.ai.AiMemory.learnHomeDishes(d)
            saved.firstOrNull()?.let { app.shoku.data.PhotoStore.addHomeRef(it, d) }
        }
        pendingPhotos = emptyList(); proposed = emptyList(); dishware = null
        val entries = items.mapIndexed { i, it ->
            // Reference dishes become library foods (with their serving portion) on first use; something the
            // AI only estimated becomes a library food too, so it's a normal match next time.
            val (refType, refId) = it.reference?.let { r -> RefType.FOOD to repo.adoptReference(r) }
                ?: if (it.refType == RefType.QUICK && it.per100 != null) RefType.FOOD to repo.foodForEstimate(it.name, it.per100, brand = it.brand)
                else (it.refType to it.refId)
            LogEntry(
                id = ShokuRepository.newId(), date = date.toString(), meal = meal.value,
                refType = refType, refId = refId, name = it.name, unit = it.unit, qty = it.qty,
                kcal = it.nutrients?.kcal, protein = it.nutrients?.protein, fibre = it.nutrients?.fibre,
                carbs = it.nutrients?.carbs, fat = it.nutrients?.fat,
                status = status, basis = it.basis, source = "ai", createdAt = now + i, updatedAt = now + i,
                // A brand on something matched to a generic ("Yadav" chakli) is kept as the entry's brand note.
                variant = it.brand?.takeIf { _ -> it.refType != RefType.QUICK },
                photos = photoNames,
                note = if (where == "outside") "Eaten out" else null,
            )
        }
        entries.forEach { repo.add(it) }
        if (entries.isNotEmpty()) added.value = added.value + listOf(entries)
        ai.value = AiState.Idle
    }

    private fun foodSuggestion(food: Food, library: Library?): Suggestion {
        val unit = food.unit
        val qty = food.baseQty.takeIf { it > 0 } ?: 1.0
        return Suggestion(
            RefType.FOOD, food.id, food.name, "Scanned · per ${formatUnitQty(food.baseQty, food.unit)}",
            unit, qty, food.perUnit() * qty, generic = food.kind == FoodKind.GENERIC,
            directFood = food.takeIf { library?.foods?.containsKey(it.id) != true },
        )
    }

    fun undoLast() = viewModelScope.launch {
        val last = added.value.lastOrNull() ?: return@launch
        repo.deleteAll(last)
        added.value = added.value.dropLast(1)
    }
}
