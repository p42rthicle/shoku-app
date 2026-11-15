package app.shoku

import app.shoku.data.db.Batch
import app.shoku.data.db.Food
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.PlanSlot
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Library
import app.shoku.domain.PlanState
import app.shoku.domain.SLOT
import app.shoku.domain.planEntries
import app.shoku.domain.prepNeeds
import app.shoku.domain.mealHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PlanTest {
    private val chicken = Food("c", "Chicken breast fillet", "g", 100.0, 104.0, 22.0, 0.0)
    private val soya = Food("s", "Soya chunks", "g", 100.0, 354.0, 53.0, 13.0)
    private val paneer = Food("p", "Paneer, low-fat milk", "g", 100.0, 240.0, 26.0, 0.0)
    private val sat = LocalDate.of(2026, 11, 14) // Saturday
    private val dal = Batch("b", null, "Mix Dal", sat.minusDays(1).toString(), 4.0, totalKcal = 800.0, totalProtein = 40.0, totalFibre = 20.0)
    private val lib = Library(listOf(chicken, soya, paneer), emptyList(), emptyList(), listOf(dal), emptyList())
    private val plan = PlanState(
        listOf(MealTemplate("l", "Chicken lunch", "Lunch"), MealTemplate("t", "Soya lunch", "Lunch"), MealTemplate("d", "Paneer dinner", "Dinner")),
        listOf(
            TemplateItem("1", "l", "food", "c", "", "g", 100.0), TemplateItem("2", "t", "food", "s", "", "g", 30.0),
            TemplateItem("3", "d", "food", "p", "", "g", 80.0), TemplateItem("4", "d", SLOT, null, "Dal", "serving", 1.0),
        ),
        (1..7).map { PlanSlot("$it|Lunch", it, "Lunch", if (it == 2) "t" else "l") } + (1..7).map { PlanSlot("$it|Dinner", it, "Dinner", "d") },
    )

    @Test
    fun dayPlanUsesWeekdayTemplatesAndFridgeBatch() {
        val p = lib.planEntries(plan, sat, emptyList(), "planned", emptySet(), listOf("Breakfast", "Lunch", "Snacks", "Dinner"))
        assertEquals(listOf("Chicken breast fillet", "Paneer, low-fat milk", "Mix Dal"), p.entries.map { it.name })
        assertEquals(200.0, p.entries.last().kcal!!, 0.01) // one of 4 servings
        val tue = lib.planEntries(plan, sat.plusDays(3), emptyList(), "planned", setOf("Dinner"), listOf("Lunch", "Dinner"))
        assertEquals(listOf("Soya chunks"), tue.entries.map { it.name })
    }

    @Test
    fun prepCountsChickenPaneerAndBatchServings() {
        val need = lib.prepNeeds(plan, sat.plusDays(2), 3) // Mon, Tue, Wed
        assertEquals(200.0, need.chickenG, 0.01)
        assertEquals(240.0, need.paneerG, 0.01)
        assertEquals(3.0, need.batchServings, 0.01)
    }

    @Test
    fun todayOverrideSwapsOrSkipsAMeal() {
        val swapped = lib.planEntries(plan, sat, emptyList(), "planned", emptySet(), listOf("Lunch", "Dinner"), mapOf("Lunch" to "t", "Dinner" to null))
        assertEquals(listOf("Soya chunks"), swapped.entries.map { it.name })
    }

    @Test
    fun historyFindsRepeatedMealsAndTheUsualCore() {
        fun e(date: String, food: String, qty: Double) = app.shoku.data.db.LogEntry("$date$food", date, "Lunch", "food", food, food, "g", qty, 100.0, 10.0, 1.0)
        val logs = listOf(
            e("2026-11-01", "c", 100.0), e("2026-11-01", "s", 30.0),
            e("2026-11-02", "c", 100.0), e("2026-11-02", "s", 30.0),
            e("2026-11-03", "c", 120.0), e("2026-11-03", "p", 80.0),
        )
        val h = lib.mealHistory(logs, "Lunch", LocalDate.of(2026, 11, 10), PlanState.EMPTY)
        assertEquals(1, h.size) // the usual core (chicken + soya); the same pair isn't listed twice
        assertEquals(2, h.first().times)
        assertEquals(100.0, h.first().items.first { it.refId == "c" }.qty, 0.01) // the usual amount
    }
}
