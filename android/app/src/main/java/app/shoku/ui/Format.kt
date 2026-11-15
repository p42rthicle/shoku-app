package app.shoku.ui

import app.shoku.domain.PortionMetrics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** 2.0 -> "2", 0.5 -> "0.5", 142.04 -> "142", 1.25 -> "1.25". */
fun formatQty(v: Double): String {
    if (abs(v - round(v)) < 1e-9) return round(v).toLong().toString()
    return if (abs(v) >= 20) round(v).toLong().toString()
    else String.format(Locale.UK, "%.2f", v).trimEnd('0').trimEnd('.')
}

fun formatUnitQty(qty: Double, unit: String): String = when (unit) {
    PortionMetrics.SERVING -> "${formatQty(qty)} ${if (qty == 1.0) "serving" else "servings"}"
    "" -> formatQty(qty)
    else -> "${formatQty(qty)} $unit"
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.UK)
private val shortDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

fun LocalDate.longLabel(): String = format(dayFormat)
fun LocalDate.shortLabel(): String = format(shortDayFormat)
fun LocalDate.dayMonth(): String = format(dayMonthFormat)

/** "Today", "Yesterday", "Tomorrow", or the weekday name within a week, else the date. */
fun LocalDate.relativeTitle(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    // Always the date otherwise: "Saturday" alone looked like today when today is also a Saturday.
    else -> format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
}

/** The meal it is now on [date], from the meal times in Settings (hidden meals skipped). */
fun mealForNow(date: java.time.LocalDate = java.time.LocalDate.now()): String =
    app.shoku.data.MealSettingsStore.current.value.mealAt(java.time.LocalTime.now(), date)
