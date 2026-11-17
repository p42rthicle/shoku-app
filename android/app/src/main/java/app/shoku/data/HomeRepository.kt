package app.shoku.data

import app.shoku.data.db.Meals
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.StockPlaces
import app.shoku.data.db.Supplement
import app.shoku.data.db.SupplementDose
import app.shoku.domain.ScheduleRule
import app.shoku.domain.encodeSchedule
import app.shoku.domain.isDue
import app.shoku.domain.PlanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Supplements with their stock row and doses, as one snapshot for screens and reminders. */
data class SupplementState(
    val supplements: List<Supplement>,
    val doses: List<SupplementDose>,
    val stock: List<StockItem>,
    val moves: List<StockMove>,
) {
    fun stockItem(s: Supplement) = stock.firstOrNull { it.supplementId == s.id }
    fun taken(s: Supplement, date: LocalDate) = doses.any { it.supplementId == s.id && it.date == date.toString() && it.taken }
    /** Supplements due with [slot] on [date], in display order. */
    fun due(slot: String, date: LocalDate) = supplements.filter { it.slot == slot && it.isDue(date) }
}

/** A spice runs low at a fifth of what there was (100 g pack: low at 20 g). */
fun spiceLow(place: String, qty: Double?): Double? =
    if (place == StockPlaces.SPICES && qty != null && qty > 0) Math.round(qty * 0.2).toDouble().coerceAtLeast(1.0) else null

/** Supplements, meal plan and home stock (database version 4). */
class HomeRepository(private val db: ShokuDatabase, scope: CoroutineScope) {
    private val home = db.home()

    val supplements: Flow<SupplementState> = combine(home.observeSupplements(), home.observeDoses(), home.observeStock(), home.observeMoves(), ::SupplementState)
        .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    suspend fun setTaken(s: Supplement, date: LocalDate, taken: Boolean) = home.upsertDoses(
        listOf(SupplementDose("${s.id}|$date", s.id, date.toString(), s.dose, taken)),
    )

    /** Marks everything due with [slot] on [date] as taken; returns their names. */
    suspend fun takeAll(slot: String, date: LocalDate, only: Set<String>? = null): List<String> {
        val st = supplements.first()
        val due = st.due(slot, date).filter { !st.taken(it, date) && (only == null || it.id in only) }
        if (due.isNotEmpty()) home.upsertDoses(due.map { SupplementDose("${it.id}|$date", it.id, date.toString(), it.dose, true) })
        return due.map { it.name }
    }

    suspend fun saveSupplement(s: Supplement) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        home.upsertSupplements(listOf(s.copy(updatedAt = now)))
        // A removed supplement takes its stock line with it.
        if (s.deleted) home.upsertStock(home.allStock().filter { it.supplementId == s.id && !it.deleted }.map { it.copy(deleted = true, updatedAt = now) })
    }

    /** "I have N now": a fresh count of a supplement's stock. */
    /** "I had [qty] on [on]": a count on a day (today = right now); doses ticked since then come off it. */
    suspend fun countSupplement(s: Supplement, qty: Double, on: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        val now = if (on >= LocalDate.now()) System.currentTimeMillis() else on.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val existing = home.allStock().firstOrNull { it.supplementId == s.id && !it.deleted }
        home.upsertStock(listOf(
            existing?.copy(countQty = qty, countedAt = now, updatedAt = now)
                ?: StockItem(stockId(s.id), s.name, supplementId = s.id, unit = s.unit, place = StockPlaces.SUPPLEMENTS, countQty = qty, countedAt = now, updatedAt = now),
        ))
    }

    private fun stockId(supplementId: String) = "stock-$supplementId"

    // ---------- Meal plan ----------

    val plan: Flow<app.shoku.domain.PlanState> = combine(home.observeTemplates(), home.observeTemplateItems(), home.observePlanSlots(), ::PlanState)
        .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    /** Saves a template and replaces its item list (old items not in [items] are soft-deleted). */
    suspend fun saveTemplate(t: app.shoku.data.db.MealTemplate, items: List<app.shoku.data.db.TemplateItem>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        home.upsertTemplates(listOf(t.copy(updatedAt = now)))
        val keep = items.mapIndexed { i, it -> it.copy(templateId = t.id, position = i, updatedAt = now) }
        val gone = home.allTemplateItems().filter { it.templateId == t.id && !it.deleted && keep.none { k -> k.id == it.id } }.map { it.copy(deleted = true, updatedAt = now) }
        home.upsertTemplateItems(keep + gone)
    }

    /** Uses template [t] for [meal] on [weekdays] (1 = Monday); days it no longer covers are cleared. */
    suspend fun setWeekdays(t: app.shoku.data.db.MealTemplate, weekdays: Set<Int>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val slots = home.allPlanSlots()
        val rows = (1..7).mapNotNull { d ->
            val id = "$d|${t.meal}"
            val old = slots.firstOrNull { it.id == id }
            when {
                d in weekdays -> app.shoku.data.db.PlanSlot(id, d, t.meal, t.id, now)
                old?.templateId == t.id -> old.copy(templateId = null, updatedAt = now)
                else -> null
            }
        }
        home.upsertPlanSlots(rows)
    }

    /** Uses [templateId] (or nothing) for [meal] on one [weekday]. */
    suspend fun setDay(weekday: Int, meal: String, templateId: String?) =
        home.upsertPlanSlots(listOf(app.shoku.data.db.PlanSlot("$weekday|$meal", weekday, meal, templateId)))

    suspend fun deleteTemplate(t: app.shoku.data.db.MealTemplate) {
        setWeekdays(t, emptySet())
        home.upsertTemplates(listOf(t.copy(deleted = true, updatedAt = System.currentTimeMillis())))
    }

    // ---------- Home stock ----------

    val stock: Flow<Pair<List<StockItem>, List<StockMove>>> = combine(home.observeStock(), home.observeMoves(), ::Pair)
        .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    /**
     * Adds [qty] (in the food's own unit) of [food]: a purchase on its existing item, or a new item counted
     * now. Spices are counted too ("40 g left"), low at a fifth of the pack, with spoon sizes for recipes.
     */
    suspend fun addFoodStock(food: app.shoku.data.db.Food, qty: Double, chosenPlace: String? = null) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = home.allStock().firstOrNull { it.foodId == food.id && !it.deleted }
        when {
            existing == null -> {
                val place = chosenPlace ?: placeFor(food)
                if (place == StockPlaces.SPICES) addSpoons(food)
                home.upsertStock(listOf(StockItem(
                    java.util.UUID.randomUUID().toString(), food.name, foodId = food.id, unit = food.unit, place = place,
                    tracked = true, countQty = qty, countedAt = now, status = "have", lowAt = spiceLow(place, qty), updatedAt = now,
                )))
            }
            // Status-only or never counted: what was just bought is how much there is now.
            !existing.tracked || existing.countQty == null -> home.upsertStock(listOf(existing.copy(
                tracked = true, countQty = qty, countedAt = now, status = "have", lowAt = existing.lowAt ?: spiceLow(existing.place, qty), updatedAt = now,
            )))
            else -> home.upsertMoves(listOf(StockMove(java.util.UUID.randomUUID().toString(), existing.id, qty, now, "bought")))
        }
    }

    /** Recipes measure spices in spoons: a spice in grams without them gets the usual sizes (1 tsp ≈ 2.5 g). */
    suspend fun addSpoons(food: app.shoku.data.db.Food) = withContext(Dispatchers.IO) {
        if (food.unit != "g") return@withContext
        val have = db.library().allPortions().filter { it.foodId == food.id && !it.deleted }.map { it.label }
        val add = listOf("tsp" to 2.5, "tbsp" to 7.5).filter { it.first !in have }
            .map { (l, q) -> app.shoku.data.db.FoodPortion(java.util.UUID.nameUUIDFromBytes("portion|${food.id}|$l".toByteArray()).toString(), food.id, l, q) }
        if (add.isNotEmpty()) db.library().upsertPortions(add)
    }

    /**
     * One time (0.9.59): status-only stock (spices: "have / low / out") becomes counted. The amount isn't
     * known yet, so the row asks for it ("Amount not set"); out is 0. Spices get their spoon sizes.
     */
    suspend fun countStatusItems() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val foods = db.library().allFoods().associateBy { it.id }
        val rows = home.allStock().filter { !it.deleted && !it.tracked && it.supplementId == null }
        rows.forEach { i -> if (i.place == StockPlaces.SPICES) i.foodId?.let(foods::get)?.let { addSpoons(it) } }
        home.upsertStock(rows.map { it.copy(tracked = true, countQty = if (it.status == "out") 0.0 else null, countedAt = now, updatedAt = now) })
    }

    suspend fun saveStockItem(item: StockItem) = home.upsertStock(listOf(item.copy(updatedAt = System.currentTimeMillis())))

    /** The stock row for a food, if it has one. */
    suspend fun stockFor(foodId: String): StockItem? = withContext(Dispatchers.IO) { home.allStock().firstOrNull { it.foodId == foodId && !it.deleted } }

    /** Takes [qty] (in the food's unit) back off a food's stock, e.g. a bill line that shouldn't have been added. */
    suspend fun takeOut(foodId: String, qty: Double, note: String) = withContext(Dispatchers.IO) {
        val item = home.allStock().firstOrNull { it.foodId == foodId && !it.deleted && it.tracked } ?: return@withContext
        home.upsertMoves(listOf(StockMove(java.util.UUID.randomUUID().toString(), item.id, -qty, System.currentTimeMillis(), note)))
    }

    /** "There's N now": a fresh count (purchases and use before now no longer matter). */
    suspend fun recount(item: StockItem, qty: Double) = System.currentTimeMillis().let { now ->
        home.upsertStock(listOf(item.copy(countQty = qty, countedAt = now, status = "have", updatedAt = now)))
    }

    suspend fun deleteStockItem(item: StockItem) = home.upsertStock(listOf(item.copy(deleted = true, updatedAt = System.currentTimeMillis())))

    /** How much of this food was added to stock last time (a bought pack, else the first count), or null. */
    suspend fun lastBought(foodId: String): Double? = withContext(Dispatchers.IO) {
        val rows = home.allStock().filter { it.foodId == foodId }
        val ids = rows.map { it.id }.toSet()
        home.allMoves().filter { it.itemId in ids && !it.deleted && it.delta > 0 }.maxByOrNull { it.at }?.delta
            ?: rows.filter { (it.countQty ?: 0.0) > 0 }.maxByOrNull { it.countedAt }?.countQty
    }

    /** Removes a food's stock rows (it became a supplement). */
    suspend fun deleteFoodStock(foodId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        home.upsertStock(home.allStock().filter { it.foodId == foodId && !it.deleted }.map { it.copy(deleted = true, updatedAt = now) })
    }

    /** A first guess at where a food lives; editable. */
    fun placeFor(food: app.shoku.data.db.Food): String {
        val n = app.shoku.domain.searchKey("${food.name} ${food.aliases}")
        return when {
            // Clearly a spice first ("chilli flakes", "paprika powder"); then fresh vegetables and herbs, which share
            // words with spices ("red bell pepper", "fresh coriander", "green chilli") but are counted, in the fridge.
            listOf("masala", "powder", "flakes", "ground", "pulver", "gemahlen", "spice", "jeera", "cumin", "haldi", "turmeric", "hing", "garam", "seasoning", "marinade").any { it in n } -> StockPlaces.SPICES
            listOf("bell pepper", "capsicum", "paprika rot", "paprika gelb", "paprika grun", "red pepper", "green pepper", "yellow pepper", "fresh", "green chilli", "cucumber", "gurke", "tomato", "tomate", "coriander", "koriander", "spinach", "lettuce", "salad", "zucchini", "carrot", "karotte", "mushroom", "pilz").any { it in n } -> StockPlaces.FRIDGE
            listOf("chilli", "pepper", "salt").any { it in n } -> StockPlaces.SPICES
            listOf("frozen", "tiefkuhl", "fries", "pommes", "nugget", "ice cream", "gelato", "pizza").any { it in n } -> StockPlaces.FREEZER
            listOf("milk", "yogurt", "joghurt", "skyr", "paneer", "egg", "chicken", "cheese", "kase", "butter", "cream", "kefir", "tofu", "curd", "pudding").any { it in n } -> StockPlaces.FRIDGE
            else -> StockPlaces.PANTRY
        }
    }

    private companion object { const val SLOT_TYPE = app.shoku.domain.SLOT }
}
