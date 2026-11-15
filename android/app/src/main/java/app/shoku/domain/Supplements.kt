package app.shoku.domain

import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.Supplement
import app.shoku.data.db.SupplementDose
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One schedule rule: every day, every week on [day], or the first [day] of each month. Rules apply in
 * order; a rule with [until] stops after that date and the next one takes over (D3: weekly, then monthly).
 */
@Serializable
data class ScheduleRule(val every: String, val day: String? = null, val until: String? = null) {
    companion object {
        const val DAY = "day"
        const val WEEK = "week"
        const val MONTH = "month"
    }
}

private val scheduleJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

fun parseSchedule(s: String): List<ScheduleRule> =
    runCatching { scheduleJson.decodeFromString(ListSerializer(ScheduleRule.serializer()), s) }.getOrDefault(emptyList())

fun encodeSchedule(rules: List<ScheduleRule>): String = scheduleJson.encodeToString(ListSerializer(ScheduleRule.serializer()), rules)

private fun dayOf(code: String?): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.startsWith(code.orEmpty().uppercase().take(3)) && code != null }

/** No schedule: taken now and then, logged by hand. */
val Supplement.asNeeded: Boolean get() = parseSchedule(schedule).isEmpty()

/** Whether [s] is due on [date]: inside its start/end dates, and the rule in force that day matches. */
fun Supplement.isDue(date: LocalDate): Boolean {
    if (!active || deleted) return false
    val d = date.toString()
    if (d < startDate || (endDate != null && d > endDate)) return false
    val rule = parseSchedule(schedule).firstOrNull { it.until == null || d <= it.until } ?: return false
    return when (rule.every) {
        ScheduleRule.DAY -> true
        ScheduleRule.WEEK -> date.dayOfWeek == dayOf(rule.day)
        ScheduleRule.MONTH -> date.dayOfWeek == dayOf(rule.day) && date.dayOfMonth <= 7
        else -> false
    }
}

/** "Daily", "Sundays until 3 Jan, then the first Sunday of each month". */
fun Supplement.scheduleText(): String {
    fun dayName(code: String?) = dayOf(code)?.getDisplayName(TextStyle.FULL, Locale.UK) ?: "?"
    val parts = parseSchedule(schedule).map { r ->
        val what = when (r.every) {
            ScheduleRule.DAY -> "daily"
            ScheduleRule.WEEK -> "${dayName(r.day)}s"
            ScheduleRule.MONTH -> "the first ${dayName(r.day)} of each month"
            else -> r.every
        }
        what + (r.until?.let { " until ${LocalDate.parse(it).let { d -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.UK)}" }}" } ?: "")
    }
    return parts.joinToString(", then ").replaceFirstChar { it.uppercase() }.ifEmpty { "As needed" }
}

/**
 * Stock of a supplement now: the last count, minus doses taken after it, plus purchases/corrections
 * after it. Null when it was never counted.
 */
fun supplementStock(item: StockItem?, doses: List<SupplementDose>, moves: List<StockMove>): Double? {
    val count = item?.countQty ?: return null
    val used = doses.filter { it.supplementId == item.supplementId && it.taken && !it.deleted && it.updatedAt > item.countedAt }.sumOf { it.qty }
    val added = moves.filter { it.itemId == item.id && !it.deleted && it.at > item.countedAt }.sumOf { it.delta }
    return count - used + added
}

/** The last day the stock covers, following the schedule from [from] (null if it lasts over two years). */
fun Supplement.lastsUntil(stock: Double, from: LocalDate): LocalDate? {
    var left = stock
    var day = from
    var last: LocalDate? = null
    repeat(730) {
        if (isDue(day)) {
            if (left < dose - 1e-9) return last ?: from.minusDays(1)
            left -= dose
            last = day
        }
        day = day.plusDays(1)
    }
    return null
}

/** Supplements on one day: how many doses were due and how many were ticked. */
data class SupplementDay(val due: Int, val taken: Int)

/** Per day of [days]: doses due and taken, for all [supplements] or only [onlyId]. */
fun supplementDays(supplements: List<Supplement>, doses: List<SupplementDose>, days: List<LocalDate>, onlyId: String? = null): Map<LocalDate, SupplementDay> {
    val list = supplements.filter { onlyId == null || it.id == onlyId }
    val taken = doses.filter { it.taken && !it.deleted }.map { it.supplementId to it.date }.toSet()
    return days.associateWith { d ->
        val due = list.filter { it.isDue(d) }
        SupplementDay(due.size, due.count { (it.id to d.toString()) in taken })
    }
}

/**
 * Days in a row with every due dose taken, counting back from [today]. Days with nothing due don't
 * break it; today only counts once it's complete.
 */
fun supplementStreak(supplements: List<Supplement>, doses: List<SupplementDose>, today: LocalDate, onlyId: String? = null): Int {
    val days = (0L..400L).map { today.minusDays(it) }
    val stats = supplementDays(supplements, doses, days, onlyId)
    var streak = 0
    for (d in days) {
        val s = stats.getValue(d)
        if (s.due == 0) continue
        if (s.taken == s.due) streak++ else if (d != today) break
    }
    return streak
}
