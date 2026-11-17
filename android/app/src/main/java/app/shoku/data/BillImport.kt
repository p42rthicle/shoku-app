package app.shoku.data

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.shoku.R
import app.shoku.ShokuApplication
import app.shoku.data.ai.AiBillItem
import app.shoku.data.ai.AiClient
import app.shoku.data.db.Food
import app.shoku.data.db.RefType
import app.shoku.data.notify.Reminders
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.data.reference.toLibraryFood
import app.shoku.domain.inFoodUnit
import app.shoku.ui.formatUnitQty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A supermarket bill (screenshots, a photo, or shared from the Lidl app) straight into stock:
 * - lines that are clearly one of your foods go onto its stock, nothing to check;
 * - new things become foods (Open Food Facts for packaged products, the food databases for loose fruit
 *   and vegetables, an AI estimate last), their stock is added too, and they wait in the review list;
 * - not food (toiletries, clothes) is listed as not added.
 * Runs in the app's scope, so leaving the screen doesn't stop it; a notification says when it's done.
 */
object BillImport {
    sealed interface State {
        data object Idle : State
        data class Working(val step: String) : State
        data class Failed(val message: String) : State
        /** Read, but the same lines as the bill added at [earlier]: nothing added unless you say "Add it again". */
        data class Duplicate(val earlier: Long) : State
    }

    /** One line of a bill, as added. [source]: "yours", "off" (Open Food Facts), "database" or "estimate". */
    @Serializable
    data class Line(
        val id: String,
        val said: String,
        val name: String,
        val foodId: String,
        /** Amount added, in the food's unit. */
        val qty: Double,
        val unit: String,
        val source: String,
        /** The bill made this food (so removing the line can remove the food too). */
        val created: Boolean = false,
        /** The bill made the stock row (no stock of it before). */
        val newStock: Boolean = false,
        val reviewed: Boolean = false,
        val brand: String? = null,
    ) {
        val amount get() = formatUnitQty(qty, unit)
    }

    @Serializable
    data class Bill(val at: Long, val lines: List<Line>, val skipped: List<String> = emptyList()) {
        val toReview get() = lines.filter { it.source != "yours" && !it.reviewed }
    }

    val state = MutableStateFlow<State>(State.Idle)
    val bills = MutableStateFlow<List<Bill>>(emptyList())
    /** Opened from the notification: show the review straight away. */
    val openReview = MutableStateFlow(false)

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("bills", Context.MODE_PRIVATE)

    fun init(context: Context) {
        ctx = context.applicationContext
        bills.value = runCatching { json.decodeFromString(ListSerializer(Bill.serializer()), prefs.getString("bills", "[]")!!) }.getOrDefault(emptyList())
    }

    private fun save(list: List<Bill>) {
        // The last few bills; older ones only once fully reviewed.
        val kept = list.sortedByDescending { it.at }.filterIndexed { i, b -> i < 3 || b.toReview.isNotEmpty() }.take(10)
        bills.value = kept
        prefs.edit().putString("bills", json.encodeToString(ListSerializer(Bill.serializer()), kept)).apply()
    }

    /** All lines still to check, newest bill first. */
    fun pending(): Int = bills.value.sumOf { it.toReview.size }

    /** Reads [uris] (screenshots or photos of one bill) and adds it to stock. */
    fun start(shoku: ShokuApplication, uris: List<Uri>) {
        if (state.value is State.Working || uris.isEmpty()) return
        waiting = null
        state.value = State.Working("Reading your bill…")
        shoku.appScope.launch {
            try {
                val pieces = uris.flatMap { app.shoku.ui.components.billPieces(shoku, it) }.take(8)
                run(shoku, pieces)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                state.value = State.Failed(e.message ?: "Couldn't read the bill")
                notify(shoku, "Couldn't read the bill", e.message ?: "Try again, or a clearer screenshot.")
            }
        }
    }

    /** What adding a read bill needs; tests swap in their own (no network). */
    class Deps(
        val repo: ShokuRepository,
        val home: HomeRepository,
        val reference: app.shoku.data.reference.ReferenceData?,
        /** Open Food Facts' best hit for a product search. */
        val offSearch: suspend (String) -> app.shoku.data.reference.ScannedProduct? = { q -> OpenFoodFacts.search(q, 3).firstOrNull() },
        /** Between Open Food Facts lookups: it asks apps to go slow. */
        val pause: suspend () -> Unit = { delay(1500) },
    )

    private suspend fun run(shoku: ShokuApplication, pieces: List<String>) {
        val ai = AiClient()
        val lib = shoku.repository.library.first()
        val keys = ai.libraryKeys(lib)
        val result = ai.bill(pieces, keys)
        // The same receipt shared twice would double the stock: ask first.
        sameAs(result, bills.value)?.let { earlier ->
            waiting = result
            state.value = State.Duplicate(earlier.at)
            notify(shoku, "Already added?", "This bill looks like the one you added on ${dayTime(earlier.at)}. Nothing was added yet.")
            return
        }
        addRead(shoku, result)
    }

    /** A bill held back as a duplicate, waiting for "Add it again". */
    private var waiting: app.shoku.data.ai.AiBillResult? = null

    /** "Add it again": the bill held back as a duplicate goes into stock after all. */
    fun addAgain(shoku: ShokuApplication) {
        val result = waiting ?: return
        waiting = null
        state.value = State.Working("Adding your bill…")
        shoku.appScope.launch {
            try { addRead(shoku, result) } catch (e: Throwable) {
                if (e is CancellationException) throw e
                state.value = State.Failed(e.message ?: "Couldn't add the bill")
            }
        }
    }

    private suspend fun addRead(shoku: ShokuApplication, result: app.shoku.data.ai.AiBillResult) {
        val lib = shoku.repository.library.first()
        val keys = AiClient().libraryKeys(lib)
        val bill = add(Deps(shoku.repository, shoku.home, shoku.reference), lib, keys, result) { state.value = State.Working(it) }
        val lines = bill.lines
        // Nothing readable (a dark or blurry photo, not a bill): say so instead of "0 items".
        if (lines.isEmpty() && bill.skipped.isEmpty()) {
            val msg = "No food found on that bill. Try a sharper photo, or a screenshot from the store's app."
            state.value = State.Failed(msg)
            notify(shoku, "Couldn't read the bill", msg)
            return
        }
        save(listOf(bill) + bills.value)
        state.value = State.Idle
        val mine = lines.count { it.source == "yours" }
        val fresh = lines.size - mine
        notify(
            shoku, "Bill added: ${lines.size} ${if (lines.size == 1) "item" else "items"} to stock",
            listOfNotNull(
                if (mine > 0) "$mine matched your foods" else null,
                if (fresh > 0) "$fresh new, review them" else null,
                if (result.skipped.isNotEmpty()) "${result.skipped.size} not food, not added" else null,
            ).joinToString(" · "),
        )
    }

/**
     * Adds a read bill to stock: your foods straight onto their stock; new things as foods (Open Food Facts
     * for packaged products, the databases for loose ones, the estimate last), with "pc" from [AiBillItem.pieceGrams].
     */
    suspend fun add(d: Deps, lib: app.shoku.domain.Library, keys: AiClient.LibraryKeys, result: app.shoku.data.ai.AiBillResult, step: (String) -> Unit = {}): Bill {
        val repo = d.repo
        val home = d.home
        val lines = ArrayList<Line>()
        var offCalls = 0
        for ((i, item) in result.items.withIndex()) {
            step("Adding ${i + 1} of ${result.items.size}: ${item.name}")
            val ref = item.matchKey?.let { keys.refs[it] }
            val own = ref?.takeIf { it.first == RefType.FOOD }?.let { lib.foods[it.second] }
            if (own != null) {
                val qty = amountIn(lib, own, item) ?: continue
                val newStock = home.stockFor(own.id) == null
                home.addFoodStock(own, qty)
                lines += Line(newId(), item.said, own.name, own.id, qty, own.unit, "yours", newStock = newStock, brand = own.brand)
                continue
            }
            var food: Food? = null
            var source = "estimate"
            var packQty: Double? = null
            // Found again (the same Open Food Facts product, the same database food): yours already, kept as it is.
            var existed = false
            if (item.packaged && !item.search.isNullOrBlank()) {
                if (offCalls++ > 0) d.pause()
                step("Looking up ${item.name} on Open Food Facts…")
                val hit = runCatching { item.brand?.let { b -> d.offSearch("${item.search} $b") } ?: d.offSearch(item.search!!) }.getOrNull()
                if (hit != null) {
                    val (f, portions) = hit.toLibraryFood()
                    val mine = repo.library.first().foods[f.id]
                    if (mine != null) {
                        food = mine; source = "yours"; existed = true
                    } else {
                        repo.saveFood(f, updatePast = false)
                        if (portions.isNotEmpty()) repo.savePortions(portions)
                        food = f; source = "off"
                    }
                    packQty = hit.packQty
                }
            }
            if (food == null && !item.packaged && d.reference != null) {
                val r = (item.search?.let { d.reference.search(it, 3) }.orEmpty() + d.reference.search(item.name, 3)).firstOrNull()
                if (r != null) {
                    val before = repo.library.first().foods.keys
                    val id = repo.adoptReference(r)
                    existed = id in before
                    food = repo.library.first { it.foods.containsKey(id) }.foods.getValue(id); source = if (existed) "yours" else "database"
                }
            }
            if (food == null) {
                val e = item.estimate
                val unit = if (item.unit == "ml") "ml" else "g"
                food = Food(
                    ShokuRepository.newId(), item.name, unit, 100.0, e?.kcal, e?.protein, e?.fibre, carbs = e?.carbs, fat = e?.fat,
                    brand = item.brand?.takeIf { it.isNotBlank() }, source = "ai-estimate",
                )
                repo.saveFood(food, updatePast = false)
            }
            // A piece (a pepper, a cucumber) as a unit, so it can be counted in pieces.
            val piece = item.pieceGrams?.takeIf { it > 0 && food.unit in app.shoku.domain.WEIGHT_UNITS }
            if (piece != null && source != "off") {
                val has = repo.library.first().portionsOf(food).any { it.label == "pc" }
                if (!has) repo.savePortions(listOf(app.shoku.data.db.FoodPortion(ShokuRepository.newId(), food.id, "pc", piece)))
            }
            val now = repo.library.first { it.foods.containsKey(food.id) }
            val qty = (if (item.packs != null && packQty != null && packQty > 0) item.packs * packQty else null)
                ?: amountIn(now, food, item) ?: continue
            packQty?.let { PackSizes.remember(food.id, it) }
            val newStock = home.stockFor(food.id) == null
            home.addFoodStock(food, qty)
            lines += Line(newId(), item.said, food.name, food.id, qty, food.unit, source, created = !existed, newStock = newStock, brand = food.brand)
        }
        return Bill(System.currentTimeMillis(), lines, result.skipped)
    }

    /** Keeps a bill (or Say it's lines) for review. */
    fun keep(bill: Bill) = save(listOf(bill) + bills.value)

    /** The bill line's amount in the food's own unit. */
    private fun amountIn(lib: app.shoku.domain.Library, food: Food, item: AiBillItem): Double? {
        if (item.unit == food.unit) return item.qty
        lib.inFoodUnit(food.id, item.unit, item.qty)?.let { return it }
        // The estimate's grams (the whole amount bought) work for a food kept in g or ml.
        return item.estimate?.grams?.takeIf { food.unit in app.shoku.domain.WEIGHT_UNITS && it > 0 }
    }

    private fun newId() = java.util.UUID.randomUUID().toString()

    private fun update(lineId: String, change: (Line) -> Line?) {
        save(bills.value.map { b -> b.copy(lines = b.lines.mapNotNull { if (it.id == lineId) change(it) else it }) })
    }

    fun looksRight(line: Line) = update(line.id) { it.copy(reviewed = true) }

    fun allLookRight() = save(bills.value.map { b -> b.copy(lines = b.lines.map { it.copy(reviewed = true) }) })

    /** Takes a line back out of stock (and its food, when the bill made it and nothing else uses it). */
    suspend fun remove(shoku: ShokuApplication, line: Line) = remove(shoku.repository, shoku.home, line)

    suspend fun remove(repo: ShokuRepository, home: HomeRepository, line: Line) {
        undoStock(repo, home, line)
        update(line.id) { null }
    }

    /** Replaced by another food (its stock was just added): the line's own stock and food go. */
    suspend fun replaced(shoku: ShokuApplication, line: Line, with: Food, qty: Double) = replaced(shoku.repository, shoku.home, line, with, qty)

    suspend fun replaced(repo: ShokuRepository, home: HomeRepository, line: Line, with: Food, qty: Double) {
        undoStock(repo, home, line)
        // The chosen food may have had stock already: removing this line later takes only [qty] back off.
        update(line.id) { it.copy(foodId = with.id, name = with.name, qty = qty, unit = with.unit, source = "yours", created = false, newStock = false, reviewed = true, brand = with.brand) }
    }

    private suspend fun undoStock(repo: ShokuRepository, home: HomeRepository, line: Line) {
        if (line.newStock) home.stockFor(line.foodId)?.let { home.deleteStockItem(it) }
        else home.takeOut(line.foodId, line.qty, "not from the bill")
        if (line.created) {
            val food = repo.library.first().foods[line.foodId] ?: return
            val used = repo.allEntries.first().any { !it.deleted && it.refId == food.id }
            if (!used) repo.deleteFood(food)
        }
    }

    /**
     * One time (0.9.56): bill items filed under Spices as have/low/out by mistake ("Red bell pepper" read as
     * pepper) go to where they belong, counted with the bill's amount.
     */
    suspend fun repairPlaces(repo: ShokuRepository, home: HomeRepository) {
        val lib = repo.library.first()
        for (line in bills.value.flatMap { it.lines }.filter { it.newStock }) {
            val item = home.stockFor(line.foodId) ?: continue
            val food = lib.foods[line.foodId] ?: continue
            val place = home.placeFor(food)
            // Wrongly status-only (have/low/out) and not a spice: counted again with the bill's amount; a place
            // chosen since (Fridge) is kept, only Spices moves.
            if (!item.tracked && place != app.shoku.data.db.StockPlaces.SPICES) {
                val keep = item.place.takeIf { it != app.shoku.data.db.StockPlaces.SPICES } ?: place
                home.saveStockItem(item.copy(place = keep, tracked = true, countQty = line.qty, countedAt = System.currentTimeMillis(), unit = food.unit))
            }
        }
    }

    /** Lines from Say it that made new foods, so they show up for checking like a bill's. */
    fun record(lines: List<Line>) {
        if (lines.isEmpty()) return
        save(listOf(Bill(System.currentTimeMillis(), lines)) + bills.value)
    }

    /** A "not food" line being added after all: off the list. */
    fun dropSkipped(text: String) = save(bills.value.map { b -> b.copy(skipped = b.skipped - text) })

    fun dismissFailure() {
        if (state.value is State.Failed || state.value is State.Duplicate) { state.value = State.Idle; waiting = null }
    }

    /**
     * The earlier bill [result] repeats, if any: most of its printed lines the same (a receipt shared or
     * photographed twice). Weighed things print their weight, so a week's identical shop rarely matches;
     * when it does, "Add it again" is one tap.
     */
    fun sameAs(result: app.shoku.data.ai.AiBillResult, earlier: List<Bill>): Bill? {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val now = (result.items.map { it.said } + result.skipped).map(::key).filter { it.isNotEmpty() }
        if (now.size < 3) return null
        return earlier.firstOrNull { b ->
            val before = (b.lines.map { it.said } + b.skipped).map(::key).toMutableList()
            val same = now.count { before.remove(it) }
            same >= 0.8 * now.size
        }
    }

    fun dayTime(at: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()))

    /** Undo a whole bill: every line back out of stock (as Remove does), and the bill is forgotten. */
    suspend fun undo(shoku: ShokuApplication, bill: Bill) = undo(shoku.repository, shoku.home, bill)

    suspend fun undo(repo: ShokuRepository, home: HomeRepository, bill: Bill) {
        for (l in bill.lines) undoStock(repo, home, l)
        save(bills.value.filter { it.at != bill.at })
    }

    private fun notify(shoku: ShokuApplication, title: String, text: String) {
        Reminders.ensureChannel(shoku)
        val n = NotificationCompat.Builder(shoku, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(Reminders.linkIntent(shoku, "shoku://stock?review=bill", 5100))
            .build()
        Reminders.post(shoku, 5100, n)
    }
}
