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
     * now. Spices become status-only items ("have").
     */
    suspend fun addFoodStock(food: app.shoku.data.db.Food, qty: Double, chosenPlace: String? = null) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = home.allStock().firstOrNull { it.foodId == food.id && !it.deleted }
        when {
            existing == null -> {
                val place = chosenPlace ?: placeFor(food)
                val spice = place == StockPlaces.SPICES
                home.upsertStock(listOf(StockItem(
                    java.util.UUID.randomUUID().toString(), food.name, foodId = food.id, unit = food.unit, place = place,
                    tracked = !spice, countQty = if (spice) null else qty, countedAt = now, status = "have", updatedAt = now,
                )))
            }
            !existing.tracked -> home.upsertStock(listOf(existing.copy(status = "have", updatedAt = now)))
            else -> home.upsertMoves(listOf(StockMove(java.util.UUID.randomUUID().toString(), existing.id, qty, now, "bought")))
        }
    }

    suspend fun saveStockItem(item: StockItem) = home.upsertStock(listOf(item.copy(updatedAt = System.currentTimeMillis())))

    /** "There's N now": a fresh count (purchases and use before now no longer matter). */
    suspend fun recount(item: StockItem, qty: Double) = System.currentTimeMillis().let { now ->
        home.upsertStock(listOf(item.copy(countQty = qty, countedAt = now, status = "have", updatedAt = now)))
    }

    suspend fun deleteStockItem(item: StockItem) = home.upsertStock(listOf(item.copy(deleted = true, updatedAt = System.currentTimeMillis())))

    /** Removes a food's stock rows (it became a supplement). */
    suspend fun deleteFoodStock(foodId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        home.upsertStock(home.allStock().filter { it.foodId == foodId && !it.deleted }.map { it.copy(deleted = true, updatedAt = now) })
    }

    /** A first guess at where a food lives; editable. */
    fun placeFor(food: app.shoku.data.db.Food): String {
        val n = app.shoku.domain.searchKey("${food.name} ${food.aliases}")
        return when {
            listOf("masala", "powder", "spice", "jeera", "cumin", "haldi", "turmeric", "chilli", "pepper", "salt", "hing", "garam", "seasoning", "marinade").any { it in n } -> StockPlaces.SPICES
            listOf("frozen", "tiefkuhl", "fries", "pommes", "nugget", "ice cream", "gelato", "pizza").any { it in n } -> StockPlaces.FREEZER
            listOf("milk", "yogurt", "joghurt", "skyr", "paneer", "egg", "chicken", "cheese", "kase", "butter", "cream", "kefir", "tofu", "curd", "pudding").any { it in n } -> StockPlaces.FRIDGE
            else -> StockPlaces.PANTRY
        }
    }

    private companion object { const val SLOT_TYPE = app.shoku.domain.SLOT }
}
