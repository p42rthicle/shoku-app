package app.shoku.domain

import app.shoku.data.db.Basis
import app.shoku.data.db.Batch
import app.shoku.data.db.FoodKind
import app.shoku.data.db.LogEntry
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.PlanSlot
import app.shoku.data.db.RefType
import app.shoku.data.db.TemplateItem
import java.time.LocalDate
import java.util.UUID

/** Template item type for "whatever sabzi or dal batch is in the fridge", picked when the plan is added. */
const val SLOT = "slot"

/** The meal plan: templates, their items, and which template each weekday's meal uses. */
data class PlanState(val templates: List<MealTemplate>, val items: List<TemplateItem>, val slots: List<PlanSlot>) {
    fun itemsOf(templateId: String) = items.filter { it.templateId == templateId && !it.deleted }.sortedBy { it.position }
    fun templateFor(weekday: Int, meal: String): MealTemplate? =
        slots.firstOrNull { it.weekday == weekday && it.meal == meal && !it.deleted }?.templateId?.let { id -> templates.firstOrNull { it.id == id && !it.deleted } }
    fun weekdaysOf(templateId: String): List<Int> = slots.filter { it.templateId == templateId && !it.deleted }.map { it.weekday }.sorted()
    val isEmpty get() = templates.none { !it.deleted }

    companion object { val EMPTY = PlanState(emptyList(), emptyList(), emptyList()) }
}

/** Sum of a template's items; slot items count as nothing (the batch isn't known yet). */
fun Library.templateTotals(items: List<TemplateItem>): Nutrients =
    items.filter { it.refType != SLOT && it.refId != null }.fold(Nutrients.ZERO) { acc, i -> acc + (entryNutrients(i.refType, i.refId!!, i.unit, i.qty) ?: Nutrients.UNKNOWN) }

/** The newest unfinished batch cooked in the last week that still has more than a bite left. */
fun Library.fridgeBatch(date: LocalDate, logs: List<LogEntry>): Batch? {
    val batchLogs = logs.filter { it.refType == RefType.BATCH && !it.deleted }
    return batches.values
        .filter { !it.finished && !it.deleted && it.date in date.minusDays(7).toString()..date.toString() }
        .filter { b -> val m = batchMetrics(b); batchConsumption(m, b.id, batchLogs).fractionLeft(m) > 0.05 }
        .maxByOrNull { it.date }
}

/** What adding a day's plan would log, and the slot items that had no batch to use. */
data class PlannedDay(val entries: List<LogEntry>, val missingSlots: List<String>)

/**
 * Entries for the plan of [date] as [status], skipping [skipMeals] (already logged or hidden).
 * Slot items use the fridge batch, one serving.
 */
fun Library.planEntries(
    plan: PlanState,
    date: LocalDate,
    logs: List<LogEntry>,
    status: String,
    skipMeals: Set<String>,
    meals: List<String>,
    /** Today-only choices: meal -> template id, or null to leave that meal out. */
    overrides: Map<String, String?> = emptyMap(),
): PlannedDay {
    val batch = fridgeBatch(date, logs)
    val missing = ArrayList<String>()
    val now = System.currentTimeMillis()
    var n = 0
    val entries = meals.filter { it !in skipMeals }.flatMap { meal ->
        val t = (if (meal in overrides) overrides[meal]?.let { id -> plan.templates.firstOrNull { it.id == id && !it.deleted } } else plan.templateFor(date.dayOfWeek.value, meal))
            ?: return@flatMap emptyList()
        plan.itemsOf(t.id).mapNotNull { item ->
            val (type, id, unit, qty) = if (item.refType == SLOT) {
                if (batch == null) { missing += item.label; return@mapNotNull null }
                Quad(RefType.BATCH, batch.id, PortionMetrics.SERVING, item.qty)
            } else Quad(item.refType, item.refId ?: return@mapNotNull null, item.unit, item.qty)
            val name = nameOf(type, id) ?: return@mapNotNull null
            val nut = entryNutrients(type, id, unit, qty)
            val generic = type == RefType.FOOD && foods[id]?.kind == FoodKind.GENERIC
            LogEntry(
                id = UUID.randomUUID().toString(), date = date.toString(), meal = meal, refType = type, refId = id, name = name,
                unit = unit, qty = qty, kcal = nut?.kcal, protein = nut?.protein, fibre = nut?.fibre, carbs = nut?.carbs, fat = nut?.fat,
                status = status, source = "plan", basis = if (generic) Basis.TYPICAL else Basis.EXACT,
                createdAt = now + n, updatedAt = now + n++,
            )
        }
    }
    return PlannedDay(entries, missing.distinct())
}

private data class Quad(val type: String, val id: String, val unit: String, val qty: Double)

/** What to prepare for the days [from] .. [from]+[days)-1: chicken to marinate, paneer to make, batch servings to cook. */
data class PrepNeeds(val chickenG: Double, val paneerG: Double, val batchServings: Double) {
    /** Milk for the paneer: 1 L of 1.5% milk gives about 200 g. */
    val milkL get() = paneerG / 200.0
}

fun Library.prepNeeds(plan: PlanState, from: LocalDate, days: Int): PrepNeeds {
    var chicken = 0.0; var paneer = 0.0; var slots = 0.0
    repeat(days) { i ->
        val day = from.plusDays(i.toLong()).dayOfWeek.value
        app.shoku.data.db.Meals.ALL.forEach { meal ->
            val t = plan.templateFor(day, meal) ?: return@forEach
            plan.itemsOf(t.id).forEach { item ->
                if (item.refType == SLOT) { slots += item.qty; return@forEach }
                val f = item.refId?.let { foods[it] } ?: return@forEach
                val grams = if (item.unit == f.unit) item.qty else portionsOf(f).firstOrNull { it.label == item.unit }?.qty?.times(item.qty) ?: return@forEach
                val name = searchKey(f.name)
                if (f.unit == "g" && "chicken" in name) chicken += grams
                if (f.unit == "g" && "paneer" in name) paneer += grams
            }
        }
    }
    return PrepNeeds(chicken, paneer, slots)
}
