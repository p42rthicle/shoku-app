package app.shoku

import app.shoku.data.db.LogEntry
import app.shoku.domain.MealSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MealPlanTest {
    private val today = LocalDate.of(2026, 11, 14)

    private fun entry(date: LocalDate, meal: String) =
        LogEntry("x", date.toString(), meal, "food", "f", "Toast", "pc", 1.0, 70.0, 2.0, 1.0)

    @Test
    fun mealByTimeSkipsHiddenMeals() {
        val s = MealSettings()
        assertEquals("Breakfast", s.mealAt(LocalTime.of(9, 0), today))
        assertEquals("Snacks", s.mealAt(LocalTime.of(16, 0), today))
        assertEquals("Dinner", s.mealAt(LocalTime.of(1, 0), today)) // before breakfast: still dinner
        val noSnacks = s.hide("Snacks", today, null)
        assertEquals("Lunch", noSnacks.mealAt(LocalTime.of(16, 0), today))
        assertEquals("Snacks", noSnacks.mealAt(LocalTime.of(16, 0), today.minusDays(1))) // before the range
    }

    @Test
    fun hiddenMealsStayWhereSomethingWasLogged() {
        val s = MealSettings().hide("Snacks", today.minusDays(10), today.minusDays(2))
        assertFalse("Snacks" in s.visibleMeals(today.minusDays(5)))
        assertTrue("Snacks" in s.visibleMeals(today.minusDays(5), listOf(entry(today.minusDays(5), "Snacks"))))
        assertTrue("Snacks" in s.visibleMeals(today)) // range ended
    }

    @Test
    fun showingAgainEndsTheRangeYesterday() {
        val s = MealSettings().hide("Snacks", today.minusDays(10), null).showFrom("Snacks", today)
        assertEquals(today.minusDays(1).toString(), s.hidden.single().to)
        assertTrue(MealSettings().hide("Snacks", today, null).showFrom("Snacks", today).hidden.isEmpty())
    }

    @Test
    fun customStartTimes() {
        val s = MealSettings(starts = MealSettings.DEFAULT_STARTS + ("Lunch" to 12 * 60 + 30))
        assertEquals("Breakfast", s.mealAt(LocalTime.of(12, 0), today))
        assertEquals("Lunch", s.mealAt(LocalTime.of(12, 30), today))
    }
}
