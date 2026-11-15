package app.shoku

import app.shoku.data.db.Supplement
import app.shoku.domain.ScheduleRule
import app.shoku.domain.encodeSchedule
import app.shoku.domain.isDue
import app.shoku.domain.lastsUntil
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SupplementTest {
    private val d3 = Supplement(
        "d3", "Vitamin D3", schedule = encodeSchedule(listOf(ScheduleRule("week", "SUN", "2027-01-03"), ScheduleRule("month", "SUN"))),
        startDate = "2026-11-15",
    )

    @Test
    fun d3WeeklyThenFirstSundayMonthly() {
        val due = generateSequence(LocalDate.of(2026, 11, 14)) { it.plusDays(1) }.takeWhile { it <= LocalDate.of(2027, 3, 14) }.filter { d3.isDue(it) }.map { it.toString() }.toList()
        assertEquals(
            listOf("2026-11-15", "2026-11-22", "2026-11-29", "2026-12-06", "2026-12-13", "2026-12-20", "2026-12-27", "2027-01-03", "2027-02-07", "2027-03-07"),
            due,
        )
    }

    @Test
    fun runOutDateFollowsTheSchedule() {
        val zinc = Supplement("z", "Zinc", schedule = encodeSchedule(listOf(ScheduleRule("day"))), startDate = "2026-11-14")
        assertEquals(LocalDate.of(2027, 3, 13), zinc.lastsUntil(120.0, LocalDate.of(2026, 11, 14)))
        assertEquals(LocalDate.of(2026, 11, 29), d3.lastsUntil(3.0, LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun streakSkipsDaysWithNothingDueAndAnUnfinishedToday() {
        val zinc = Supplement("z", "Zinc", schedule = encodeSchedule(listOf(ScheduleRule("day"))), startDate = "2026-11-10")
        val today = LocalDate.of(2026, 11, 14)
        fun dose(d: String) = app.shoku.data.db.SupplementDose("z|$d", "z", d, 1.0)
        val doses = listOf(dose("2026-11-11"), dose("2026-11-12"), dose("2026-11-13"))
        assertEquals(3, app.shoku.domain.supplementStreak(listOf(zinc), doses, today)) // today not ticked yet: doesn't break it
        assertEquals(4, app.shoku.domain.supplementStreak(listOf(zinc), doses + dose("2026-11-14"), today))
        assertEquals(3, app.shoku.domain.supplementStreak(listOf(d3), listOf(app.shoku.data.db.SupplementDose("d3|2026-11-15", "d3", "2026-11-15", 1.0), app.shoku.data.db.SupplementDose("d3|2026-11-22", "d3", "2026-11-22", 1.0), app.shoku.data.db.SupplementDose("d3|2026-11-29", "d3", "2026-11-29", 1.0)), LocalDate.of(2026, 12, 2)))
    }
}
