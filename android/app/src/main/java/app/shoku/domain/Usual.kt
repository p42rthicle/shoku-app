package app.shoku.domain

import app.shoku.data.db.Basis
import app.shoku.data.db.FoodKind
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import java.time.LocalDate

/** One item of a usual meal, with the amount usually eaten. */
data class UsualItem(val refType: String, val refId: String, val name: String, val unit: String, val qty: Double, val nutrients: Nutrients?, val basis: String)

/**
 * What [meal] usually is: items eaten in that meal on at least 40% of the days it was logged in the
 * six weeks up to the last time it was logged, each with its most common amount. Empty with fewer than 3 such days, or when the
 * pattern is too loose to be worth one tap. Batches and quick entries are left out (they change).
 */
fun usualMeal(all: List<LogEntry>, meal: String, today: LocalDate, library: Library, days: Long = 42, limit: Int = 8): List<UsualItem> {
    val to = today.toString()
    val candidates = all.filter {
        !it.deleted && it.meal == meal && it.date < to && it.refId != null &&
            (it.refType == RefType.FOOD || it.refType == RefType.DISH)
    }
    // Six weeks back from the last time this meal was logged, so a break from logging doesn't erase the habit.
    val last = candidates.maxOfOrNull { it.date }?.let(LocalDate::parse) ?: return emptyList()
    val from = last.minusDays(days).toString()
    val entries = candidates.filter { it.date >= from }
    val dayCount = entries.map { it.date }.distinct().size
    if (dayCount < 3) return emptyList()
    return entries.groupBy { it.refType to it.refId!! }
        .map { (key, list) -> key to list }
        .filter { (_, list) -> list.map { it.date }.distinct().size >= dayCount * 0.4 }
        .sortedByDescending { (_, list) -> list.map { it.date }.distinct().size }
        .mapNotNull { (key, list) ->
            val (refType, refId) = key
            val food = if (refType == RefType.FOOD) library.foods[refId] else null
            if (food != null && (food.archived || food.deleted)) return@mapNotNull null
            if (refType == RefType.DISH && library.dishes[refId]?.let { it.archived || it.deleted } != false) return@mapNotNull null
            val name = library.nameOf(refType, refId) ?: return@mapNotNull null
            val units = library.unitsFor(refType, refId)
            val (unit, qty) = list.groupingBy { it.unit to it.qty }.eachCount().maxByOrNull { it.value }!!.key
            if (unit !in units) return@mapNotNull null
            UsualItem(refType, refId, name, unit, qty, library.entryNutrients(refType, refId, unit, qty),
                if (food?.kind == FoodKind.GENERIC) Basis.TYPICAL else Basis.EXACT)
        }
        .take(limit)
}

/**
 * The amount to start with when an entry is replaced by an item with [newUnits]: the same unit and
 * amount when the new item has that unit, otherwise the same weight in grams when both sides allow it,
 * otherwise one of the new item's first unit.
 */
fun carryAmount(old: LogEntry, library: Library, newUnits: List<String>): Pair<String, Double> {
    if (old.unit in newUnits) return old.unit to old.qty
    val oldFood = if (old.refType == RefType.FOOD) old.refId?.let { library.foods[it] } else null
    val grams = when {
        old.unit == "g" -> old.qty
        oldFood != null && oldFood.unit == "g" -> library.portionsOf(oldFood).firstOrNull { it.label == old.unit }?.let { it.qty * old.qty }
        else -> null
    }
    if (grams != null && "g" in newUnits) return "g" to Math.round(grams).toDouble()
    return (newUnits.firstOrNull() ?: old.unit) to 1.0
}
