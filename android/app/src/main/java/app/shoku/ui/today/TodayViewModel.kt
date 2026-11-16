package app.shoku.ui.today

import app.shoku.domain.lastsUntilOrNull
import app.shoku.domain.asNeeded
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Batch
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import app.shoku.data.db.RefType
import app.shoku.domain.DayTotals
import app.shoku.domain.Library
import app.shoku.domain.batchConsumption
import app.shoku.domain.eaten
import app.shoku.domain.fractionLeft
import app.shoku.domain.goalFor
import app.shoku.domain.planned
import app.shoku.domain.totalsOf
import app.shoku.domain.UsualItem
import app.shoku.domain.usualMeal
import app.shoku.domain.planEntries
import app.shoku.data.reference.toLibraryFood
import kotlinx.coroutines.flow.first
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MealUi(val meal: String, val entries: List<LogEntry>) {
    val kcal get() = entries.sumOf { it.kcal ?: 0.0 }
    val protein get() = entries.sumOf { it.protein ?: 0.0 }
    val eatenCount get() = entries.count { it.status == EntryStatus.EATEN }
}

/** [ask]: cooked 3+ days ago and not confirmed recently, so Today asks whether it's still there. */
data class FridgeBatch(val batch: Batch, val left: String, val fraction: Float, val ask: Boolean = false)

data class TodayState(
    val date: LocalDate,
    val meals: List<MealUi> = emptyList(),
    val eaten: DayTotals? = null,
    val planned: DayTotals? = null,
    val goal: Goal? = null,
    val fridge: List<FridgeBatch> = emptyList(),
    val loaded: Boolean = false,
    /** Today's usual for the meal it's time for, while that meal is still empty. */
    val usual: UsualMeal? = null,
    /** Supplements running out within two weeks (name, last day covered), soonest first. */
    val runningLow: List<Pair<String, LocalDate>> = emptyList(),
    /** Per meal: supplements due that day, and whether each was taken. */
    val supplements: Map<String, List<Pair<app.shoku.data.db.Supplement, Boolean>>> = emptyMap(),
    /** The day's plan for meals still empty, ready to add in one tap. */
    val plan: app.shoku.domain.PlannedDay? = null,
    /** Per meal still empty: the template it will use and the others to switch to (today only). */
    val planChoices: List<PlanChoice> = emptyList(),
)

data class PlanChoice(val meal: String, val current: app.shoku.data.db.MealTemplate?, val options: List<app.shoku.data.db.MealTemplate>)

data class UsualMeal(val meal: String, val items: List<UsualItem>) {
    val kcal get() = items.sumOf { it.nutrients?.kcal ?: 0.0 }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repo: ShokuRepository,
    private val prefs: android.content.SharedPreferences? = null,
    private val home: app.shoku.data.HomeRepository? = null,
) : ViewModel() {
    private val supplementFlow = home?.supplements ?: kotlinx.coroutines.flow.flowOf(app.shoku.data.SupplementState(emptyList(), emptyList(), emptyList(), emptyList()))
    /** Today-only template choices: for this date, meal -> template id (null = leave out). */
    private val overrides = MutableStateFlow<Pair<LocalDate, Map<String, String?>>>(LocalDate.now() to emptyMap())
    private val homeFlow = combine(supplementFlow, home?.plan ?: kotlinx.coroutines.flow.flowOf(app.shoku.domain.PlanState.EMPTY), overrides, ::Triple)

    /** Uses another template (or none) for one meal on the day shown; only for this "Add as planned". */
    fun choose(meal: String, templateId: String?) {
        val (d, map) = overrides.value
        overrides.value = date.value to (if (d == date.value) map else emptyMap()) + (meal to templateId)
    }
    /** Batch id -> date until which "still in the fridge?" isn't asked again. */
    private val stillThere = MutableStateFlow(prefs?.all?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap().orEmpty())

    private val date = MutableStateFlow(LocalDate.now())

    val state: StateFlow<TodayState> = date.flatMapLatest { d ->
        combine(repo.day(d), repo.library, repo.allGoals, repo.allEntries, combine(stillThere, app.shoku.data.MealSettingsStore.current, homeFlow, ::Triple)) { entries, library, goals, all, (still, meals, h) ->
            val (supps, plan, ov) = h
            val chosen = if (ov.first == d) ov.second else emptyMap()
            val skip = entries.map { it.meal }.toSet() + Meals.ALL.filter { meals.hiddenOn(it, d) }
            val dueBySlot = Meals.ALL.associateWith { m -> supps.due(m, d).map { it to supps.taken(it, d) } }.filterValues { it.isNotEmpty() }
            val base = buildState(d, entries, library, goals, all, still, meals)
            // A hidden meal still shows when a supplement is due with it (D3 with a hidden breakfast).
            val shown = base.meals.map { it.meal }.toSet()
            val withSupps = (base.meals + dueBySlot.keys.filter { it !in shown }.map { MealUi(it, emptyList()) })
                .sortedBy { Meals.ALL.indexOf(it.meal).let { i -> if (i < 0) 99 else i } }
            val today = LocalDate.now()
            val low = if (d != today) emptyList() else supps.supplements.filter { it.active && !it.asNeeded }.mapNotNull { s ->
                val left = app.shoku.domain.supplementStock(supps.stockItem(s), supps.doses, supps.moves) ?: return@mapNotNull null
                val until = s.lastsUntilOrNull(left, today) ?: return@mapNotNull null
                if (until.isAfter(today.plusDays(14))) null else s.name to until
            }.sortedBy { it.second }
            base.copy(
                meals = withSupps,
                supplements = dueBySlot,
                runningLow = low,
                plan = library.planEntries(plan, d, all, ShokuRepository.defaultStatusFor(d), skip, Meals.ALL, chosen).takeIf { it.entries.isNotEmpty() },
                planChoices = Meals.ALL.filter { it !in skip }.mapNotNull { m ->
                    val options = plan.templates.filter { !it.deleted && it.meal == m }
                    if (options.isEmpty()) return@mapNotNull null
                    val current = if (m in chosen) chosen[m]?.let { id -> options.firstOrNull { it.id == id } } else plan.templateFor(d.dayOfWeek.value, m)
                    PlanChoice(m, current, options)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(LocalDate.now()))

    private fun buildState(d: LocalDate, entries: List<LogEntry>, library: Library, goals: List<Goal>, all: List<LogEntry>, still: Map<String, String>, mealSettings: app.shoku.domain.MealSettings): TodayState {
        // Hidden meals drop out, except on days something was logged in them.
        val meals = mealSettings.visibleMeals(d, entries).map { m -> MealUi(m, entries.filter { it.meal == m }) } +
            entries.filter { it.meal !in Meals.ALL }.groupBy { it.meal }.map { (m, e) -> MealUi(m, e) }
        return TodayState(
            date = d,
            meals = meals,
            eaten = totalsOf(entries.eaten()),
            planned = totalsOf(entries.planned()),
            goal = goalFor(goals, d.toString()),
            fridge = fridge(d, library, all, still),
            loaded = true,
            usual = if (d == LocalDate.now()) {
                val m = mealSettings.mealAt(java.time.LocalTime.now(), d)
                if (entries.none { it.meal == m }) usualMeal(all, m, d, library).takeIf { it.isNotEmpty() }?.let { UsualMeal(m, it) } else null
            } else null,
        )
    }

    /** Unfinished batches cooked by [d] that still have something left (same rule as Add and Library). */
    private fun fridge(d: LocalDate, library: Library, all: List<LogEntry>, still: Map<String, String> = emptyMap()): List<FridgeBatch> {
        val to = d.toString()
        val batchLogs = all.filter { it.refType == RefType.BATCH }
        return library.batches.values
            .filter { !it.finished && !it.deleted && it.date <= to }
            .mapNotNull { b ->
                val m = library.batchMetrics(b)
                val c = batchConsumption(m, b.id, batchLogs)
                val fraction = c.fractionLeft(m)
                if (fraction < 0.02) return@mapNotNull null
                val left = if (c.remainingWeight != null) "${formatQty(c.remainingWeight)} ${m.finalUnit} left"
                else "${formatUnitQty(c.remainingServings, "serving")} left"
                val ask = d == LocalDate.now() && b.date <= d.minusDays(3).toString() && (still[b.id] ?: "") < d.toString()
                FridgeBatch(b, left, fraction.toFloat(), ask)
            }
            .sortedByDescending { it.batch.date }
    }

    /** "Still there": don't ask again for two days. */
    fun keepBatch(b: Batch) {
        val until = LocalDate.now().plusDays(2).toString()
        prefs?.edit()?.putString(b.id, until)?.apply()
        stillThere.value = stillThere.value + (b.id to until)
    }

    fun finishBatch(b: Batch) = viewModelScope.launch { repo.setBatchFinished(b, true) }

    fun setDate(d: LocalDate) { date.value = d }
    fun shiftDay(days: Long) { date.value = date.value.plusDays(days) }

    fun toggle(entry: LogEntry) = viewModelScope.launch {
        repo.setStatus(entry, if (entry.status == EntryStatus.EATEN) EntryStatus.PLANNED else EntryStatus.EATEN)
    }

    /** Logs the whole usual meal as eaten, as one group so Undo removes it all. */
    fun logUsual(u: UsualMeal, done: (List<LogEntry>) -> Unit) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val d = date.value.toString()
        val entries = u.items.mapIndexed { i, it ->
            LogEntry(
                id = ShokuRepository.newId(), date = d, meal = u.meal, refType = it.refType, refId = it.refId, name = it.name,
                unit = it.unit, qty = it.qty, kcal = it.nutrients?.kcal, protein = it.nutrients?.protein, fibre = it.nutrients?.fibre,
                carbs = it.nutrients?.carbs, fat = it.nutrients?.fat, status = EntryStatus.EATEN, basis = it.basis, source = "usual",
                createdAt = now + i, updatedAt = now + i,
            )
        }
        entries.forEach { repo.add(it) }
        done(entries)
    }

    /**
     * Makes sure a picked replacement is in the library (database dishes and online products are
     * copied in first) and hands back its type and id once the library has it.
     */
    fun resolvePick(pick: ReplacePick, done: (String, String) -> Unit) = viewModelScope.launch {
        val (type, id) = repo.resolvePick(pick)
        done(type, id)
    }

    /** Adds the day's plan as one group; [done] gets the entries for Undo. */
    fun addPlan(p: app.shoku.domain.PlannedDay, done: (List<LogEntry>) -> Unit) = viewModelScope.launch {
        p.entries.forEach { repo.add(it) }
        overrides.value = date.value to emptyMap()
        done(p.entries)
    }

    fun toggleSupplement(s: app.shoku.data.db.Supplement, taken: Boolean) = viewModelScope.launch { home?.setTaken(s, date.value, !taken) }

    fun restoreAll(entries: List<LogEntry>) = viewModelScope.launch { entries.forEach { repo.restore(it) } }

    fun undoAll(entries: List<LogEntry>) = viewModelScope.launch { repo.deleteAll(entries) }

    fun delete(entry: LogEntry) = viewModelScope.launch { repo.delete(entry) }

    /** How many log entries use a food (for "Update past entries"). */
    suspend fun usageCount(foodId: String): Int = repo.allEntries.first().count { it.refType == RefType.FOOD && it.refId == foodId }
    fun saveFood(food: app.shoku.data.db.Food, updatePast: Boolean, done: (Int) -> Unit) = viewModelScope.launch { done(repo.saveFood(food, updatePast)) }
    fun savePortions(rows: List<app.shoku.data.db.FoodPortion>) = viewModelScope.launch { repo.savePortions(rows) }
    fun restore(entry: LogEntry) = viewModelScope.launch { repo.restore(entry) }
    /** A photo of a meal without an estimate: saved with each of the meal's entries on the day shown. */
    fun attachPhotos(meal: String, base64s: List<String>) = viewModelScope.launch {
        val names = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { base64s.map { app.shoku.data.PhotoStore.save(it) } }
        state.value.meals.firstOrNull { it.meal == meal }?.entries.orEmpty().forEach { e ->
            val all = (e.photos.orEmpty().split(',').filter { it.isNotBlank() } + names).distinct().joinToString(",")
            repo.update(e.copy(photos = all))
        }
    }

    fun save(entry: LogEntry) = viewModelScope.launch {
        // An AI entry corrected here teaches the next estimate (AiMemory).
        state.value.meals.flatMap { it.entries }.firstOrNull { it.id == entry.id }?.let { app.shoku.data.ai.AiMemory.learnFromEdit(it, entry) }
        repo.update(entry)
    }
}
