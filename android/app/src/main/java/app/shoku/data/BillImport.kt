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

    private suspend fun run(shoku: ShokuApplication, pieces: List<String>) {
        val repo = shoku.repository
        val home = shoku.home
        val ai = AiClient()
        val lib = repo.library.first()
        val keys = ai.libraryKeys(lib)
        val result = ai.bill(pieces, keys)
        val lines = ArrayList<Line>()
        var offCalls = 0
        for ((i, item) in result.items.withIndex()) {
            state.value = State.Working("Adding ${i + 1} of ${result.items.size}: ${item.name}")
            val ref = item.matchKey?.let { keys.refs[it] }
            val own = ref?.takeIf { it.first == RefType.FOOD }?.let { lib.foods[it.second] }
            if (own != null) {
                val qty = amountIn(lib, own, item) ?: continue
                val newStock = home.stockFor(own.id) == null
                home.addFoodStock(own, qty)
                lines += Line(newId(), item.said, own.name, own.id, qty, own.unit, "yours", newStock = newStock, brand = own.brand)
                continue
            }
            // New: Open Food Facts for packaged products (gently: it asks apps to go slow), else the databases, else the estimate.
            var food: Food? = null
            var source = "estimate"
            var packQty: Double? = null
            if (item.packaged && !item.search.isNullOrBlank()) {
                if (offCalls++ > 0) delay(1500)
                state.value = State.Working("Looking up ${item.name} on Open Food Facts…")
                val hit = runCatching {
                    item.brand?.let { b -> OpenFoodFacts.search("${item.search} $b", 3).firstOrNull() }
                        ?: OpenFoodFacts.search(item.search!!, 3).firstOrNull()
                }.getOrNull()
                if (hit != null) {
                    val (f, portions) = hit.toLibraryFood()
                    repo.saveFood(f, updatePast = false)
                    if (portions.isNotEmpty()) repo.savePortions(portions)
                    food = f; source = "off"; packQty = hit.packQty
                }
            }
            if (food == null && !item.packaged) {
                val r = (item.search?.let { shoku.reference.search(it, 3) }.orEmpty() + shoku.reference.search(item.name, 3)).firstOrNull()
                if (r != null) {
                    val id = repo.adoptReference(r)
                    food = repo.library.first { it.foods.containsKey(id) }.foods.getValue(id); source = "database"
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
            val now = repo.library.first { it.foods.containsKey(food.id) }
            val qty = (if (item.packs != null && packQty != null && packQty > 0) item.packs * packQty else null)
                ?: amountIn(now, food, item) ?: continue
            packQty?.let { PackSizes.remember(food.id, it) }
            home.addFoodStock(food, qty)
            lines += Line(newId(), item.said, food.name, food.id, qty, food.unit, source, created = true, newStock = true, brand = food.brand)
        }
        save(listOf(Bill(System.currentTimeMillis(), lines, result.skipped)) + bills.value)
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
    suspend fun remove(shoku: ShokuApplication, line: Line) {
        undoStock(shoku, line)
        update(line.id) { null }
    }

    /** Replaced by another food (its stock was just added): the line's own stock and food go. */
    suspend fun replaced(shoku: ShokuApplication, line: Line, with: Food, qty: Double) {
        undoStock(shoku, line)
        update(line.id) { it.copy(foodId = with.id, name = with.name, qty = qty, unit = with.unit, source = "yours", created = false, reviewed = true, brand = with.brand) }
    }

    private suspend fun undoStock(shoku: ShokuApplication, line: Line) {
        val home = shoku.home
        val repo = shoku.repository
        if (line.newStock) home.stockFor(line.foodId)?.let { home.deleteStockItem(it) }
        else home.takeOut(line.foodId, line.qty, "not from the bill")
        if (line.created) {
            val food = repo.library.first().foods[line.foodId] ?: return
            val used = repo.allEntries.first().any { !it.deleted && it.refId == food.id }
            if (!used) repo.deleteFood(food)
        }
    }

    /** Lines from Say it that made new foods, so they show up for checking like a bill's. */
    fun record(lines: List<Line>) {
        if (lines.isEmpty()) return
        save(listOf(Bill(System.currentTimeMillis(), lines)) + bills.value)
    }

    fun dismissFailure() { if (state.value is State.Failed) state.value = State.Idle }

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
