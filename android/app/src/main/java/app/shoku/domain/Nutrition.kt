package app.shoku.domain

import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType

/*
 * Port of the calculation logic in shoku_streamlit/app.py.
 * Pure Kotlin, no Android types, so it is unit-tested against the real CSV data.
 */

/** Null means unknown. Adding an unknown makes the sum unknown, like the Python app. */
data class Nutrients(
    val kcal: Double?,
    val protein: Double?,
    val fibre: Double?,
    val carbs: Double?,
    val fat: Double?,
) {
    operator fun plus(o: Nutrients) = Nutrients(
        add(kcal, o.kcal), add(protein, o.protein), add(fibre, o.fibre), add(carbs, o.carbs), add(fat, o.fat),
    )

    operator fun times(f: Double) = Nutrients(kcal?.times(f), protein?.times(f), fibre?.times(f), carbs?.times(f), fat?.times(f))

    operator fun div(d: Double) = times(1.0 / d)

    companion object {
        val ZERO = Nutrients(0.0, 0.0, 0.0, 0.0, 0.0)
        val UNKNOWN = Nutrients(null, null, null, null, null)
        private fun add(a: Double?, b: Double?) = if (a == null || b == null) null else a + b
    }
}

/** A reference to something edible plus an amount; shared by dish and batch ingredients. */
data class IngredientRef(val refType: String, val refId: String, val unit: String, val qty: Double)

fun DishIngredient.asRef() = IngredientRef(refType, refId, unit, qty)
fun BatchIngredient.asRef() = IngredientRef(refType, refId, unit, qty)

/** Totals for a whole recipe or cook, plus how it divides into servings and weight. */
data class PortionMetrics(
    val servings: Double,
    val finalQty: Double?,
    val finalUnit: String?,
    val yieldSource: String,
    val totals: Nutrients,
) {
    val hasWeightBasis: Boolean get() = (finalQty ?: 0.0) > 0.0 && !finalUnit.isNullOrBlank()
    val perServing: Nutrients get() = totals / servings
    val perWeightUnit: Nutrients? get() = if (hasWeightBasis) totals / finalQty!! else null
    val gramsPerServing: Double? get() = if (hasWeightBasis) finalQty!! / servings else null

    /** Units an entry can be logged in: always "serving", plus the final weight unit when known. */
    val units: List<String> get() = if (hasWeightBasis) listOf(SERVING, finalUnit!!) else listOf(SERVING)

    fun perUnit(unit: String): Nutrients? = when {
        unit == SERVING -> perServing
        hasWeightBasis && unit == finalUnit -> perWeightUnit
        else -> null
    }

    companion object {
        const val SERVING = "serving"

        fun build(
            servings: Double,
            totals: Nutrients,
            manualQty: Double?,
            manualUnit: String?,
            autoQty: Double? = null,
            autoUnit: String? = null,
        ): PortionMetrics {
            val safeServings = if (servings > 0) servings else 1.0
            return when {
                (manualQty ?: 0.0) > 0 && !manualUnit.isNullOrBlank() ->
                    PortionMetrics(safeServings, manualQty, manualUnit, "manual", totals)
                (autoQty ?: 0.0) > 0 && !autoUnit.isNullOrBlank() ->
                    PortionMetrics(safeServings, autoQty, autoUnit, "auto", totals)
                else -> PortionMetrics(safeServings, null, null, "none", totals)
            }
        }
    }
}

fun Food.perUnit(): Nutrients {
    val base = if (baseQty > 0) baseQty else 1.0
    return Nutrients(kcal, protein, fibre, carbs, fat) / base
}

fun Dish.fixedPerServing() = Nutrients(fixedKcal, fixedProtein, fixedFibre, fixedCarbs, fixedFat)

fun Batch.storedTotals() = Nutrients(totalKcal, totalProtein, totalFibre, totalCarbs, totalFat)

/**
 * If every ingredient uses the same unit, the cooked weight is assumed to be their sum
 * (the Python app's "auto" yield).
 */
fun autoYield(ingredients: List<IngredientRef>): Pair<Double, String>? {
    val usable = ingredients.filter { it.unit.isNotBlank() }
    if (usable.isEmpty()) return null
    val units = usable.map { it.unit }.distinct()
    if (units.size != 1) return null
    return usable.sumOf { it.qty } to units.single()
}

/** Read-only view of the food library with the calculations that need it. */
class Library(
    foods: List<Food>,
    dishes: List<Dish>,
    dishIngredients: List<DishIngredient>,
    batches: List<Batch>,
    batchIngredients: List<BatchIngredient>,
    portions: List<FoodPortion> = emptyList(),
) {
    val foods: Map<String, Food> = foods.associateBy { it.id }
    val dishes: Map<String, Dish> = dishes.associateBy { it.id }
    val batches: Map<String, Batch> = batches.associateBy { it.id }
    val dishIngredients: Map<String, List<DishIngredient>> = dishIngredients.groupBy { it.dishId }
    val batchIngredients: Map<String, List<BatchIngredient>> = batchIngredients.groupBy { it.batchId }
    val portions: Map<String, List<FoodPortion>> = portions.filter { !it.deleted }.groupBy { it.foodId }

    // Shared between the UI thread and background flows, so it must be thread-safe.
    private val dishCache = java.util.concurrent.ConcurrentHashMap<String, PortionMetrics>()

    /** Sum of ingredient nutrients. A dish already on [stack] is a cycle and counts as unknown. */
    fun ingredientTotals(ingredients: List<IngredientRef>, stack: Set<String> = emptySet()): Nutrients =
        ingredients.fold(Nutrients.ZERO) { acc, ing -> acc + (perUnitOf(ing.refType, ing.refId, ing.unit, stack)?.times(ing.qty) ?: Nutrients.UNKNOWN) }

    /** Nutrients for one unit of an item, or null if the item or unit doesn't exist. */
    fun perUnitOf(refType: String, refId: String, unit: String, stack: Set<String> = emptySet()): Nutrients? = when (refType) {
        // A food works in its own unit or one of its named portions; anything else is unknown, not silently wrong.
        RefType.FOOD -> foods[refId]?.let { f ->
            when {
                f.unit == unit -> f.perUnit()
                else -> portionsOf(f).firstOrNull { it.label == unit }?.let { p -> f.perUnit() * p.qty }
            }
        }
        RefType.DISH -> if (refId in stack) null else dishes[refId]?.let { dishMetrics(it, stack).perUnit(unit) }
        RefType.BATCH -> batches[refId]?.let { batchMetrics(it).perUnit(unit) }
        else -> null
    }

    fun dishMetrics(dish: Dish, stack: Set<String> = emptySet()): PortionMetrics {
        if (stack.isEmpty()) dishCache[dish.id]?.let { return it }
        val refs = dishIngredients[dish.id].orEmpty().map { it.asRef() }
        val totals = if (dish.fixed) {
            dish.fixedPerServing() * (if (dish.servings > 0) dish.servings else 1.0)
        } else {
            ingredientTotals(refs, stack + dish.id)
        }
        val auto = autoYield(refs)
        val metrics = PortionMetrics.build(dish.servings, totals, dish.yieldQty, dish.yieldUnit, auto?.first, auto?.second)
        if (stack.isEmpty()) dishCache[dish.id] = metrics
        return metrics
    }

    fun batchMetrics(batch: Batch): PortionMetrics =
        PortionMetrics.build(batch.servings, batch.storedTotals(), batch.finalQty, batch.finalUnit)

    /**
     * Recompute a batch's totals from its ingredient snapshot (or from the template for fixed dishes).
     * Mirrors rebuild_batch_from_snapshot: a manual final weight is kept, otherwise auto yield applies.
     */
    fun computeBatch(dish: Dish?, servings: Double, ingredients: List<IngredientRef>, manualQty: Double?, manualUnit: String?): PortionMetrics {
        if (dish != null && dish.fixed) {
            return PortionMetrics.build(servings, dish.fixedPerServing() * servings, manualQty, manualUnit)
        }
        val totals = ingredientTotals(ingredients, setOfNotNull(dish?.id))
        val auto = autoYield(ingredients)
        return PortionMetrics.build(servings, totals, manualQty, manualUnit, auto?.first, auto?.second)
    }

    /**
     * A food's portions: its own, plus its generic's when both use the same unit (Nutella gets "tbsp"
     * from Chocolate spread). Its own win when a label exists in both.
     */
    fun portionsOf(f: Food): List<FoodPortion> {
        val own = portions[f.id].orEmpty()
        val parent = f.parentId?.let { foods[it] }?.takeIf { it.unit == f.unit } ?: return own
        val inherited = portions[parent.id].orEmpty().filter { p -> own.none { it.label == p.label } }
        return own + inherited
    }

    /** Units an item can be logged in. */
    /** In the library and not archived (an archived copy shouldn't hide the database entry in search). */
    fun hasLive(foodId: String): Boolean = foods[foodId]?.let { !it.archived && !it.deleted } == true

    /** The unit a new amount starts in: a food's own unit (g, ml, pc), a dish's or batch's first (a serving). */
    fun defaultUnit(refType: String, refId: String): String? =
        if (refType == RefType.FOOD) foods[refId]?.unit else unitsFor(refType, refId).firstOrNull()

    fun unitsFor(refType: String, refId: String): List<String> = when (refType) {
        // Named portions first ("medium", "tbsp"), then the food's own unit, then weight units for
        // foods counted in pieces ("g" for eggs).
        RefType.FOOD -> foods[refId]?.let { f ->
            val (weights, named) = portionsOf(f).partition { it.label in WEIGHT_UNITS }
            named.sortedBy { it.qty }.map { it.label } + f.unit + weights.map { it.label }.filter { it != f.unit }
        }.orEmpty()
        RefType.DISH -> dishes[refId]?.let { dishMetrics(it).units }.orEmpty()
        RefType.BATCH -> batches[refId]?.let { batchMetrics(it).units }.orEmpty()
        else -> emptyList()
    }

    /** The generic a food belongs to, or the food itself when it is a generic. */
    fun familyOf(foodId: String): Food? = foods[foodId]?.let { f -> if (f.kind == FoodKind.GENERIC) f else f.parentId?.let { foods[it] } }

    fun portion(foodId: String, label: String): FoodPortion? = portions[foodId]?.firstOrNull { it.label == label }

    fun nameOf(refType: String, refId: String): String? = when (refType) {
        RefType.FOOD -> foods[refId]?.name
        RefType.DISH -> dishes[refId]?.name
        RefType.BATCH -> batches[refId]?.dishName
        else -> null
    }

    fun entryNutrients(refType: String, refId: String, unit: String, qty: Double): Nutrients? =
        perUnitOf(refType, refId, unit)?.times(qty)
}

/** Units that are a weight or volume rather than a household portion. */
val WEIGHT_UNITS = setOf("g", "ml")

/** How much of a batch has been logged, in servings and (when known) weight. */
data class BatchConsumption(
    val consumedServings: Double,
    val consumedWeight: Double,
    val remainingServings: Double,
    val remainingWeight: Double?,
)

fun batchConsumption(metrics: PortionMetrics, batchId: String, logs: List<LogEntry>): BatchConsumption {
    var servings = 0.0
    var weight = 0.0
    for (log in logs) {
        if (log.refType != RefType.BATCH || log.refId != batchId || log.qty <= 0) continue
        if (log.unit == PortionMetrics.SERVING) {
            servings += log.qty
            if (metrics.hasWeightBasis) weight += log.qty * metrics.finalQty!! / metrics.servings
        } else if (metrics.hasWeightBasis && log.unit == metrics.finalUnit) {
            weight += log.qty
            servings += log.qty * metrics.servings / metrics.finalQty!!
        }
    }
    return BatchConsumption(
        consumedServings = servings,
        consumedWeight = weight,
        remainingServings = (metrics.servings - servings).coerceAtLeast(0.0),
        remainingWeight = if (metrics.hasWeightBasis) (metrics.finalQty!! - weight).coerceAtLeast(0.0) else null,
    )
}

fun BatchConsumption.fractionLeft(metrics: PortionMetrics): Double =
    if (metrics.servings > 0) (remainingServings / metrics.servings).coerceIn(0.0, 1.0) else 0.0

/** Goal in force on [date]: the latest goal whose effectiveFrom is on or before it. */
fun goalFor(goals: List<Goal>, date: String): Goal? =
    goals.filter { !it.deleted && it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }

/** Sums for a list of entries. Unknown values count as 0; [missingCarbs] etc. say how many were unknown. */
data class DayTotals(
    val kcal: Double,
    val protein: Double,
    val fibre: Double,
    val carbs: Double,
    val fat: Double,
    val count: Int,
    val missingCarbs: Int,
    val missingFat: Int,
)

fun totalsOf(entries: List<LogEntry>) = DayTotals(
    kcal = entries.sumOf { it.kcal ?: 0.0 },
    protein = entries.sumOf { it.protein ?: 0.0 },
    fibre = entries.sumOf { it.fibre ?: 0.0 },
    carbs = entries.sumOf { it.carbs ?: 0.0 },
    fat = entries.sumOf { it.fat ?: 0.0 },
    count = entries.size,
    missingCarbs = entries.count { it.carbs == null },
    missingFat = entries.count { it.fat == null },
)

fun List<LogEntry>.eaten() = filter { it.status == EntryStatus.EATEN }
fun List<LogEntry>.planned() = filter { it.status == EntryStatus.PLANNED }

/** Calorie status thresholds from the Python app: on target up to the goal, close up to +4%. */
enum class GoalState { None, Good, Close, Bad }

fun calorieState(consumed: Double, goal: Double?): GoalState = when {
    goal == null || goal <= 0 -> GoalState.None
    consumed <= goal -> GoalState.Good
    consumed <= goal * 1.04 -> GoalState.Close
    else -> GoalState.Bad
}

fun proteinState(consumed: Double, goal: Double?): GoalState = when {
    goal == null || goal <= 0 -> GoalState.None
    consumed >= goal -> GoalState.Good
    consumed >= goal * 0.9 -> GoalState.Close
    else -> GoalState.Bad
}
