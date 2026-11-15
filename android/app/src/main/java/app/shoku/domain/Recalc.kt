package app.shoku.domain

import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType

/**
 * Port of the Python app's recalc_* helpers: after foods, dishes or batches change,
 * work out which batches and log entries depend on them and recompute their numbers.
 *
 * [library] must already contain the changed items.
 */
object Recalc {

    data class Result(val batches: List<Batch>, val logs: List<LogEntry>)

    fun affected(
        library: Library,
        dishIngredients: List<DishIngredient>,
        batchIngredients: List<BatchIngredient>,
        logs: List<LogEntry>,
        changedFoods: Set<String> = emptySet(),
        changedDishes: Set<String> = emptySet(),
        changedBatches: Set<String> = emptySet(),
    ): Result {
        // Dishes whose ingredients (directly or through other dishes/batches) changed.
        val dishes = changedDishes.toMutableSet()
        val batches = changedBatches.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (i in dishIngredients) {
                if (i.deleted || i.dishId in dishes) continue
                if (refHit(i.refType, i.refId, changedFoods, dishes, batches)) { dishes += i.dishId; grew = true }
            }
            for (i in batchIngredients) {
                if (i.deleted || i.batchId in batches) continue
                if (refHit(i.refType, i.refId, changedFoods, dishes, batches)) { batches += i.batchId; grew = true }
            }
        }

        // Rebuild batch totals from their snapshots. Batches can contain batches, so repeat until stable.
        var lib = library
        val rebuilt = LinkedHashMap<String, Batch>()
        val ingsByBatch = batchIngredients.filter { !it.deleted }.groupBy { it.batchId }
        for (pass in 0 until 4) {
            var changed = false
            for (id in batches) {
                val b = lib.batches[id] ?: continue
                val ings = ingsByBatch[id].orEmpty().map { it.asRef() }
                if (ings.isEmpty()) continue
                val manual = b.yieldSource == "manual"
                val m = lib.computeBatch(b.dishId?.let { lib.dishes[it] }, b.servings, ings, if (manual) b.finalQty else null, if (manual) b.finalUnit else null)
                // An ingredient that no longer exists makes totals unknown; keep the stored snapshot rather than wiping it.
                if ((m.totals.kcal == null && b.totalKcal != null) || (m.totals.protein == null && b.totalProtein != null)) continue
                val nb = b.copy(
                    totalKcal = m.totals.kcal, totalProtein = m.totals.protein, totalFibre = m.totals.fibre,
                    totalCarbs = m.totals.carbs, totalFat = m.totals.fat,
                    finalQty = m.finalQty, finalUnit = m.finalUnit, yieldSource = m.yieldSource,
                    updatedAt = System.currentTimeMillis(),
                )
                val same = near(nb.totalKcal, b.totalKcal) && near(nb.totalProtein, b.totalProtein) && near(nb.totalFibre, b.totalFibre) &&
                    near(nb.totalCarbs, b.totalCarbs) && near(nb.totalFat, b.totalFat) &&
                    near(nb.finalQty, b.finalQty) && nb.finalUnit == b.finalUnit
                if (!same) { rebuilt[id] = nb; changed = true }
            }
            if (!changed) break
            lib = lib.withBatches(rebuilt.values)
        }

        // Recompute log entries that point at anything affected.
        val updatedLogs = logs.mapNotNull { e ->
            val ref = e.refId ?: return@mapNotNull null
            val hit = when (e.refType) {
                RefType.FOOD -> ref in changedFoods
                RefType.DISH -> ref in dishes
                RefType.BATCH -> ref in batches
                else -> false
            }
            if (!hit) return@mapNotNull null
            val n = lib.entryNutrients(e.refType, ref, e.unit, e.qty) ?: return@mapNotNull null
            val same = near(n.kcal, e.kcal) && near(n.protein, e.protein) && near(n.fibre, e.fibre) && near(n.carbs, e.carbs) && near(n.fat, e.fat)
            if (same) null else e.copy(kcal = n.kcal, protein = n.protein, fibre = n.fibre, carbs = n.carbs, fat = n.fat, updatedAt = System.currentTimeMillis())
        }
        return Result(rebuilt.values.toList(), updatedLogs)
    }

    /** Equal apart from floating-point noise, so unchanged rows aren't rewritten (and later re-synced). */
    private fun near(a: Double?, b: Double?) = if (a == null || b == null) a == b else kotlin.math.abs(a - b) <= 1e-6 * maxOf(1.0, kotlin.math.abs(b))

    private fun refHit(type: String, id: String, foods: Set<String>, dishes: Set<String>, batches: Set<String>) = when (type) {
        RefType.FOOD -> id in foods
        RefType.DISH -> id in dishes
        RefType.BATCH -> id in batches
        else -> false
    }
}

/** Copy of the library with some batches replaced. */
fun Library.withBatches(replaced: Collection<Batch>): Library {
    val map = batches.toMutableMap()
    replaced.forEach { map[it.id] = it }
    return Library(
        foods.values.toList(), dishes.values.toList(), dishIngredients.values.flatten(),
        map.values.toList(), batchIngredients.values.flatten(), portions.values.flatten(),
    )
}
