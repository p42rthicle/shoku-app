package app.shoku.domain

import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/** A meal hidden from [from] to [to] (inclusive; null means no end). Dates are ISO strings. */
@Serializable
data class HiddenRange(val meal: String, val from: String, val to: String? = null) {
    fun covers(date: String) = date >= from && (to == null || date <= to)
}

/**
 * Which meal it is at a given time, and which meals are in use on a given day.
 * [starts] are minutes after midnight; a meal runs until the next visible meal starts, and the time
 * before the first meal of the day belongs to the last one (1 am is still dinner).
 */
@Serializable
data class MealSettings(
    val starts: Map<String, Int> = DEFAULT_STARTS,
    val hidden: List<HiddenRange> = emptyList(),
) {
    fun startOf(meal: String): Int = starts[meal] ?: DEFAULT_STARTS.getValue(meal)

    fun hiddenOn(meal: String, date: LocalDate): Boolean = hidden.any { it.meal == meal && it.covers(date.toString()) }

    /** Meals to show on [date]: hidden ones drop out unless something was logged in them that day. */
    fun visibleMeals(date: LocalDate, entries: List<LogEntry> = emptyList()): List<String> =
        Meals.ALL.filter { m -> !hiddenOn(m, date) || entries.any { it.meal == m && !it.deleted } }

    /** The meal for a moment on [date], among the meals in use that day. */
    fun mealAt(time: LocalTime, date: LocalDate): String {
        val visible = visibleMeals(date).ifEmpty { Meals.ALL }.sortedBy { startOf(it) }
        val minutes = time.hour * 60 + time.minute
        return visible.lastOrNull { startOf(it) <= minutes } ?: visible.last()
    }

    /** Shows [meal] again from [date]: ranges covering it end the day before (or go, if they start then or later). */
    fun showFrom(meal: String, date: LocalDate): MealSettings {
        val d = date.toString()
        val yesterday = date.minusDays(1).toString()
        return copy(hidden = hidden.mapNotNull { r ->
            when {
                r.meal != meal || !r.covers(d) -> r
                r.from >= d -> null
                else -> r.copy(to = yesterday)
            }
        })
    }

    fun hide(meal: String, from: LocalDate, to: LocalDate?): MealSettings =
        copy(hidden = hidden + HiddenRange(meal, from.toString(), to?.toString()))

    companion object {
        val DEFAULT_STARTS = mapOf(Meals.BREAKFAST to 4 * 60, Meals.LUNCH to 11 * 60, Meals.SNACKS to 15 * 60, Meals.DINNER to 18 * 60)
    }
}
