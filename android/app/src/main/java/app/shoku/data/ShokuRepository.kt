package app.shoku.data

import androidx.room.withTransaction
import app.shoku.data.backup.BackupData
import app.shoku.data.backup.LegacyBackup
import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Food
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.reference.ReferenceFood
import app.shoku.domain.FoodFamilies
import app.shoku.domain.IngredientRef
import app.shoku.domain.Library
import app.shoku.domain.Recalc
import app.shoku.domain.fractionLeft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

class ShokuRepository(private val db: ShokuDatabase, scope: CoroutineScope) {

    private val lib = db.library()
    private val logs = db.logs()
    private val goals = db.goals()

    val library: Flow<Library> = combine(
        combine(lib.observeFoods(), lib.observePortions(), ::Pair),
        lib.observeDishes(), lib.observeDishIngredients(), lib.observeBatches(), lib.observeBatchIngredients(),
    ) { (f, p), d, di, b, bi -> Library(f, d, di, b, bi, p) }
        .flowOn(Dispatchers.Default)
        // One shared copy for every screen instead of each screen re-running five queries.
        .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    val allEntries: Flow<List<LogEntry>> = logs.observeAll().shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)
    val allGoals: Flow<List<Goal>> = goals.observeAll()
    val weights: Flow<List<app.shoku.data.db.WeightEntry>> = db.weights().observeAll()

    suspend fun setWeight(date: LocalDate, kg: Double) = db.weights().upsertAll(listOf(app.shoku.data.db.WeightEntry(date.toString(), kg)))
    suspend fun deleteWeight(w: app.shoku.data.db.WeightEntry) = db.weights().upsertAll(listOf(w.copy(deleted = true, updatedAt = System.currentTimeMillis())))

    fun day(date: LocalDate): Flow<List<LogEntry>> = logs.observeDay(date.toString())

    suspend fun add(entry: LogEntry) {
        logs.upsert(entry)
        if (entry.refType == app.shoku.data.db.RefType.BATCH) autoFinishBatches()
    }

    suspend fun update(entry: LogEntry) = logs.upsert(entry.copy(updatedAt = System.currentTimeMillis()))

    suspend fun setStatus(entry: LogEntry, status: String) = update(entry.copy(status = status))

    suspend fun delete(entry: LogEntry) = update(entry.copy(deleted = true))

    suspend fun restore(entry: LogEntry) = update(entry.copy(deleted = false))

    /** Copies one meal's entries from [from] to [to]; returns the new entries so they can be undone. */
    suspend fun copyMeal(from: LocalDate, meal: String, to: LocalDate, toMeal: String, status: String): List<LogEntry> {
        val now = System.currentTimeMillis()
        val copies = logs.meal(from.toString(), meal).mapIndexed { i, e ->
            e.copy(
                id = newId(), date = to.toString(), meal = toMeal, status = status, source = "copy",
                createdAt = now + i, updatedAt = now + i,
            )
        }
        logs.upsertAll(copies)
        return copies
    }

    suspend fun deleteAll(entries: List<LogEntry>) = logs.upsertAll(entries.map { it.copy(deleted = true, updatedAt = System.currentTimeMillis()) })

    suspend fun setGoal(from: LocalDate, kcal: Double, protein: Double, fibre: Double? = null) =
        goals.put(Goal(effectiveFrom = from.toString(), kcal = kcal, protein = protein, fibre = fibre))

    // ---------- Library editing ----------

    private suspend fun currentLibrary() = Library(
        lib.allFoods().filter { !it.deleted }, lib.allDishes().filter { !it.deleted },
        lib.allDishIngredients().filter { !it.deleted }, lib.allBatches().filter { !it.deleted },
        lib.allBatchIngredients().filter { !it.deleted }, lib.allPortions().filter { !it.deleted },
    )

    /** Recompute batches and log entries that depend on what changed. Call inside a transaction. */
    private suspend fun applyRecalc(foods: Set<String> = emptySet(), dishes: Set<String> = emptySet(), batches: Set<String> = emptySet()): Int {
        val r = Recalc.affected(
            currentLibrary(), lib.allDishIngredients(), lib.allBatchIngredients(),
            logs.allIncludingDeleted().filter { !it.deleted }, foods, dishes, batches,
        )
        if (r.batches.isNotEmpty()) lib.upsertBatches(r.batches)
        if (r.logs.isNotEmpty()) logs.upsertAll(r.logs)
        return r.logs.size
    }

    /** Saves a food. With [updatePast], past entries and batches that use it are recalculated; returns how many entries changed. */
    suspend fun saveFood(food: Food, updatePast: Boolean): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            lib.upsertFoods(listOf(food.copy(updatedAt = System.currentTimeMillis())))
            if (updatePast) applyRecalc(foods = setOf(food.id)) else 0
        }
    }

    /**
     * Saves a dish and its ingredient list. With [updatePast], past entries of this dish (and dishes or
     * batches built on it) are recalculated; otherwise they keep the numbers they were logged with.
     */
    suspend fun saveDish(dish: Dish, ingredients: List<IngredientRef>, updatePast: Boolean): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            lib.upsertDishes(listOf(dish.copy(updatedAt = now)))
            val old = lib.allDishIngredients().filter { it.dishId == dish.id && !it.deleted }.sortedBy { it.position }
            val changes = diffIngredients(old.map { Row(it.id, it.refType, it.refId, it.unit, it.qty, it.position) }, ingredients)
            lib.upsertDishIngredients(changes.map { c ->
                old.firstOrNull { it.id == c.id }?.copy(refType = c.refType, refId = c.refId, unit = c.unit, qty = c.qty, position = c.position, deleted = c.deleted, updatedAt = now)
                    ?: DishIngredient(c.id, dish.id, c.refType, c.refId, c.unit, c.qty, c.position, updatedAt = now)
            })
            if (updatePast) applyRecalc(dishes = setOf(dish.id)) else 0
        }
    }

    /** Flat view of an ingredient row, shared by dish and batch ingredients. */
    private data class Row(val id: String, val refType: String, val refId: String, val unit: String, val qty: Double, val position: Int, val deleted: Boolean = false)

    /**
     * Turns "old rows" plus "the new list" into the minimum set of row writes: rows are reused by position
     * and only written when something changed; extra old rows are soft-deleted. Keeps sync traffic small.
     */
    private fun diffIngredients(old: List<Row>, new: List<IngredientRef>): List<Row> = buildList {
        new.forEachIndexed { i, r ->
            val o = old.getOrNull(i)
            when {
                o == null -> add(Row(newId(), r.refType, r.refId, r.unit, r.qty, i))
                o.refType != r.refType || o.refId != r.refId || o.unit != r.unit || o.qty != r.qty || o.position != i ->
                    add(o.copy(refType = r.refType, refId = r.refId, unit = r.unit, qty = r.qty, position = i))
            }
        }
        old.drop(new.size).forEach { add(it.copy(deleted = true)) }
    }

    suspend fun duplicateDish(dish: Dish): String = withContext(Dispatchers.IO) {
        val copy = dish.copy(id = newId(), name = "${dish.name} (copy)")
        val ings = lib.allDishIngredients().filter { it.dishId == dish.id && !it.deleted }.sortedBy { it.position }
        db.withTransaction {
            lib.upsertDishes(listOf(copy))
            lib.upsertDishIngredients(ings.map { it.copy(id = newId(), dishId = copy.id) })
        }
        copy.id
    }

    suspend fun setDishArchived(dish: Dish, archived: Boolean) = lib.upsertDishes(listOf(dish.copy(archived = archived, updatedAt = System.currentTimeMillis())))
    suspend fun setFoodArchived(food: Food, archived: Boolean) = lib.upsertFoods(listOf(food.copy(archived = archived, updatedAt = System.currentTimeMillis())))

    /**
     * Saves a batch (new or edited) with its ingredient snapshot and recomputes its totals.
     * A final weight given here counts as manual; without one, the ingredient sum is used when units match.
     */
    suspend fun saveBatch(batch: Batch, ingredients: List<IngredientRef>): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val library = currentLibrary()
            val dish = batch.dishId?.let { library.dishes[it] }
            val manual = (batch.finalQty ?: 0.0) > 0 && !batch.finalUnit.isNullOrBlank()
            val m = library.computeBatch(dish, batch.servings, ingredients, if (manual) batch.finalQty else null, if (manual) batch.finalUnit else null)
            val saved = batch.copy(
                totalKcal = m.totals.kcal, totalProtein = m.totals.protein, totalFibre = m.totals.fibre,
                totalCarbs = m.totals.carbs, totalFat = m.totals.fat,
                finalQty = m.finalQty, finalUnit = m.finalUnit, yieldSource = m.yieldSource, updatedAt = now,
            )
            lib.upsertBatches(listOf(saved))
            val old = lib.allBatchIngredients().filter { it.batchId == batch.id && !it.deleted }.sortedBy { it.position }
            val changes = diffIngredients(old.map { Row(it.id, it.refType, it.refId, it.unit, it.qty, it.position) }, ingredients)
            lib.upsertBatchIngredients(changes.map { c ->
                old.firstOrNull { it.id == c.id }?.copy(refType = c.refType, refId = c.refId, unit = c.unit, qty = c.qty, position = c.position, deleted = c.deleted, updatedAt = now)
                    ?: BatchIngredient(c.id, batch.id, c.refType, c.refId, c.unit, c.qty, c.position, updatedAt = now)
            })
            applyRecalc(batches = setOf(batch.id))
            batch.id
        }
    }

    /** Marks batches finished once less than 2% is left. Returns how many it closed. */
    suspend fun autoFinishBatches(): Int = withContext(Dispatchers.IO) {
        val library = currentLibrary()
        val batchLogs = logs.allIncludingDeleted().filter { !it.deleted && it.refType == app.shoku.data.db.RefType.BATCH }
        val done = library.batches.values.filter { b ->
            if (b.finished) return@filter false
            val m = library.batchMetrics(b)
            val c = app.shoku.domain.batchConsumption(m, b.id, batchLogs)
            c.consumedServings > 0 && c.fractionLeft(m) < 0.02
        }
        if (done.isNotEmpty()) lib.upsertBatches(done.map { it.copy(finished = true, updatedAt = System.currentTimeMillis()) })
        done.size
    }

    suspend fun setBatchFinished(batch: Batch, finished: Boolean) =
        lib.upsertBatches(listOf(batch.copy(finished = finished, updatedAt = System.currentTimeMillis())))

    /** Soft-deletes a batch. Entries already logged from it keep their numbers. */
    suspend fun deleteBatch(batch: Batch) =
        lib.upsertBatches(listOf(batch.copy(deleted = true, updatedAt = System.currentTimeMillis())))

    /**
     * Groups the library into generic foods (see FoodFamilies). Safe to run more than once: it only
     * writes rows that are missing or different. Returns how many rows it wrote.
     */
    suspend fun applyFoodFamilies(): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val plan = FoodFamilies.plan(lib.allFoods(), lib.allPortions())
            if (plan.foods.isNotEmpty()) lib.upsertFoods(plan.foods)
            if (plan.portions.isNotEmpty()) lib.upsertPortions(plan.portions)
            plan.foods.size + plan.portions.size
        }
    }

    /** Copies a reference dish into the library as a generic food (once) and returns its id. */
    suspend fun adoptReference(ref: ReferenceFood): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            val existing = lib.allFoods().firstOrNull { it.id == ref.libraryId }
            when {
                existing == null -> {
                    lib.upsertFoods(listOf(ref.toFood()))
                    ref.toPortion()?.let { lib.upsertPortions(listOf(it)) }
                }
                // Picked again after it was archived: bring it back.
                existing.archived || existing.deleted -> lib.upsertFoods(listOf(existing.copy(archived = false, deleted = false, updatedAt = System.currentTimeMillis())))
            }
            ref.libraryId
        }
    }

    /**
     * A food for something the AI (or a quick entry) only estimated, so it's in the library next time:
     * an existing food with the same name, or a new one from per-100 values (source "ai-estimate",
     * listed in Library under "Check foods the AI added").
     */
    suspend fun foodForEstimate(name: String, per100: app.shoku.domain.Nutrients, unit: String = "g", brand: String? = null): String = withContext(Dispatchers.IO) {
        val key = name.trim().lowercase()
        val b = brand?.trim()?.ifEmpty { null }
        lib.allFoods().firstOrNull { !it.deleted && it.name.trim().lowercase() == key && (b == null || it.brand.equals(b, ignoreCase = true)) }?.let { f ->
            if (f.archived) lib.upsertFoods(listOf(f.copy(archived = false, updatedAt = System.currentTimeMillis())))
            return@withContext f.id
        }
        val food = Food(
            id = newId(), name = name.trim().replaceFirstChar { it.uppercase() }, unit = unit, baseQty = 100.0,
            kcal = per100.kcal, protein = per100.protein, fibre = per100.fibre, carbs = per100.carbs, fat = per100.fat,
            brand = b, kind = if (b != null) app.shoku.data.db.FoodKind.PRODUCT else app.shoku.data.db.FoodKind.GENERIC, source = "ai-estimate",
        )
        lib.upsertFoods(listOf(food))
        food.id
    }

    /**
     * One time: earlier quick or AI-estimated entries (no library food) become library foods, so they
     * show up and rank in search. Entries keep their numbers. Returns how many entries were linked.
     */
    suspend fun promoteQuickEntries(): Int = withContext(Dispatchers.IO) {
        val quick = logs.allIncludingDeleted().filter {
            !it.deleted && it.refType == app.shoku.data.db.RefType.QUICK && it.unit in app.shoku.domain.WEIGHT_UNITS && it.qty > 0 && it.kcal != null
        }
        quick.groupBy { it.name.trim().lowercase() }.values.sumOf { group ->
            val e = group.maxBy { it.createdAt }
            val f = 100.0 / e.qty
            val per100 = app.shoku.domain.Nutrients(e.kcal?.times(f), e.protein?.times(f), e.fibre?.times(f), e.carbs?.times(f), e.fat?.times(f))
            val id = foodForEstimate(e.name, per100, e.unit)
            group.forEach { logs.upsert(it.copy(refType = app.shoku.data.db.RefType.FOOD, refId = id, updatedAt = System.currentTimeMillis())) }
            group.size
        }
    }

    /**
     * Merges duplicate foods into [m].food (Settings › Duplicate foods). Past entries keep their numbers and
     * units and now point at the one food; dishes, batches, meal templates and stock follow; stock amounts
     * are converted to the merged unit. The others are deleted, their names kept as aliases for search.
     */
    suspend fun mergeFoods(m: app.shoku.domain.Duplicates.Merge): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val keepId = m.food.id
        val ids = m.gone.map { it.id }.toSet() + keepId
        val goneIds = m.gone.map { it.id }.toSet()
        db.withTransaction {
            lib.upsertFoods(listOf(m.food.copy(updatedAt = now)) + m.gone.map { it.copy(deleted = true, updatedAt = now) })
            // Products under a removed generic follow it.
            lib.upsertFoods(lib.allFoods().filter { !it.deleted && it.parentId in goneIds }.map { it.copy(parentId = keepId, updatedAt = now) })
            lib.upsertPortions(lib.allPortions().filter { !it.deleted && it.foodId in ids }.map { it.copy(deleted = true, updatedAt = now) } + m.portions)
            val moved = logs.allIncludingDeleted().filter { it.refType == app.shoku.data.db.RefType.FOOD && it.refId in goneIds }
            logs.upsertAll(moved.map { it.copy(refId = keepId, updatedAt = now) })
            lib.upsertDishIngredients(lib.allDishIngredients().filter { it.refType == app.shoku.data.db.RefType.FOOD && it.refId in goneIds }.map { it.copy(refId = keepId, updatedAt = now) })
            lib.upsertBatchIngredients(lib.allBatchIngredients().filter { it.refType == app.shoku.data.db.RefType.FOOD && it.refId in goneIds }.map { it.copy(refId = keepId, updatedAt = now) })
            val home = db.home()
            home.upsertTemplateItems(home.allTemplateItems().filter { it.refType == app.shoku.data.db.RefType.FOOD && it.refId in goneIds }.map { it.copy(refId = keepId, updatedAt = now) })
            // Stock: every row in the merged unit; two rows become one (the other's amount added as a move).
            val rows = home.allStock().filter { !it.deleted && it.foodId in ids }
            val moves = home.allMoves().filter { !it.deleted }
            fun k(row: app.shoku.data.db.StockItem) = if (row.unit == m.food.unit) 1.0 else m.factors[row.foodId] ?: 1.0
            val converted = rows.map { r ->
                val f = k(r)
                val rowMoves = moves.filter { it.itemId == r.id }.map { it.copy(delta = it.delta * f, updatedAt = now) }
                Triple(r.copy(foodId = keepId, name = m.food.name, unit = m.food.unit, countQty = r.countQty?.times(f), lowAt = r.lowAt?.times(f), updatedAt = now), rowMoves, f)
            }
            // The row already counted in the merged unit stays (no rounding from a conversion), then the one with a count.
            val main = converted.sortedWith(
                compareByDescending<Triple<app.shoku.data.db.StockItem, List<app.shoku.data.db.StockMove>, Double>> { it.third == 1.0 }
                    .thenByDescending { it.first.countQty != null }.thenByDescending { it.first.countQty ?: 0.0 },
            ).firstOrNull()
            if (main != null) {
                home.upsertStock(listOf(main.first))
                home.upsertMoves(main.second)
                converted.filter { it !== main }.forEach { (r, ms, _) ->
                    val amount = (r.countQty ?: 0.0) + ms.sumOf { it.delta }
                    if (amount > 0) home.upsertMoves(listOf(app.shoku.data.db.StockMove(UUID.randomUUID().toString(), main.first.id, amount, now, "merged")))
                    home.upsertStock(listOf(r.copy(deleted = true)))
                }
            }
            moved.size
        }
    }

    /**
     * Names once marked "[o] Chicken Curry (Pak Resto.)" for food ordered online: the dish (or food) becomes
     * "Chicken Curry" and its entries get the note "Ordered online · Pak Resto.". Numbers don't change.
     */
    suspend fun tidyOnlineOrders(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val mark = Regex("""^\s*\[o\]\s*(.+?)\s*(?:\(([^()]+)\))?\s*$""", RegexOption.IGNORE_CASE)
        fun split(name: String): Pair<String, String>? = mark.matchEntire(name)?.let { m ->
            m.groupValues[1].trim() to listOfNotNull("Ordered online", m.groupValues[2].trim().ifEmpty { null }).joinToString(" · ")
        }
        db.withTransaction {
            val dishes = lib.allDishes().filter { !it.deleted }.mapNotNull { d -> split(d.name)?.let { d to it } }
            lib.upsertDishes(dishes.map { (d, s) -> d.copy(name = s.first, updatedAt = now) })
            val foods = lib.allFoods().filter { !it.deleted }.mapNotNull { f -> split(f.name)?.let { f to it } }
            lib.upsertFoods(foods.map { (f, s) -> f.copy(name = s.first, updatedAt = now) })
            val entries = logs.allIncludingDeleted().filter { !it.deleted }.mapNotNull { e -> split(e.name)?.let { e to it } }
            logs.upsertAll(entries.map { (e, s) -> e.copy(name = s.first, note = e.note?.takeIf { it.isNotBlank() } ?: s.second, updatedAt = now) })
            dishes.size + foods.size + entries.size
        }
    }

    suspend fun savePortions(rows: List<app.shoku.data.db.FoodPortion>) = withContext(Dispatchers.IO) {
        lib.upsertPortions(rows.map { it.copy(updatedAt = System.currentTimeMillis()) })
    }

    suspend fun foodByBarcode(barcode: String): Food? = withContext(Dispatchers.IO) {
        lib.allFoods().firstOrNull { it.barcode == barcode && !it.deleted }
    }

    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) {
        val data = BackupData(
            lib.allFoods(), lib.allDishes(), lib.allDishIngredients(), lib.allBatches(), lib.allBatchIngredients(),
            logs.allIncludingDeleted(), goals.all(), portions = lib.allPortions(), weights = db.weights().all(),
        )
        val h = db.home()
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        fun <T> j(s: kotlinx.serialization.KSerializer<T>, rows: List<T>) = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(s), rows)
        // Supplements, plan and stock have no legacy CSV format: they go in as JSON.
        val extra = linkedMapOf(
            "supplements.json" to j(app.shoku.data.db.Supplement.serializer(), h.allSupplements()),
            "supplement_doses.json" to j(app.shoku.data.db.SupplementDose.serializer(), h.allDoses()),
            "meal_templates.json" to j(app.shoku.data.db.MealTemplate.serializer(), h.allTemplates()),
            "template_items.json" to j(app.shoku.data.db.TemplateItem.serializer(), h.allTemplateItems()),
            "plan_slots.json" to j(app.shoku.data.db.PlanSlot.serializer(), h.allPlanSlots()),
            "stock_items.json" to j(app.shoku.data.db.StockItem.serializer(), h.allStock()),
            "stock_moves.json" to j(app.shoku.data.db.StockMove.serializer(), h.allMoves()),
        )
        LegacyBackup.writeZip(LegacyBackup.toCsvFiles(data) + extra)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun defaultStatusFor(date: LocalDate): String =
            if (date.isAfter(LocalDate.now())) EntryStatus.PLANNED else EntryStatus.EATEN
    }
}
