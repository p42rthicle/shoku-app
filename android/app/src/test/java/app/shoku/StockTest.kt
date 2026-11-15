package app.shoku

import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.LogEntry
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.domain.Library
import app.shoku.domain.PlanState
import app.shoku.domain.shoppingList
import app.shoku.domain.stockLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StockTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val t0 = 1_000L
    private val milkAny = Food("milk", "Low-fat milk", "ml", 100.0, 47.0, 3.4, 0.0, kind = FoodKind.GENERIC)
    private val milbona = Food("milbona", "Low-fat milk", "ml", 100.0, 47.0, 3.4, 0.0, brand = "Milbona", parentId = "milk")
    private val dal = Food("dal", "Mixed dal, raw", "g", 100.0, 350.0, 24.0, 10.0)
    private val batch = Batch("b", null, "Mix Dal", "2026-11-19", 4.0, totalKcal = 700.0, totalProtein = 48.0, totalFibre = 20.0, updatedAt = 5_000)
    private val lib = Library(listOf(milkAny, milbona, dal), emptyList(), emptyList(), listOf(batch), listOf(BatchIngredient("bi", "b", "food", "dal", "g", 200.0, 0)))
    private fun eaten(food: String, qty: Double, at: Long) = LogEntry("e$at", "2026-11-19", "Lunch", "food", food, "x", "ml", qty, 0.0, 0.0, 0.0, updatedAt = at)

    @Test
    fun usesPurchasesAndFamilies() {
        val items = listOf(
            StockItem("s-milk", "Milk", foodId = "milbona", unit = "ml", countQty = 1000.0, countedAt = t0),
            StockItem("s-dal", "Dal", foodId = "dal", unit = "g", countQty = 1000.0, countedAt = t0),
        )
        val logs = listOf(eaten("milk", 300.0, 2_000), eaten("milbona", 150.0, 3_000), eaten("milbona", 999.0, 500)) // last one is before the count
        val moves = listOf(StockMove("m", "s-milk", 1000.0, at = 4_000))
        val levels = lib.stockLevels(items, moves, logs, emptyList(), PlanState.EMPTY, today).associateBy { it.item.id }
        assertEquals(1550.0, levels.getValue("s-milk").qty!!, 0.01) // 1000 - 300 (generic) - 150 + 1000
        assertEquals(800.0, levels.getValue("s-dal").qty!!, 0.01) // the cooked batch took 200 g
    }

    @Test
    fun shoppingListFlagsLowAndOut() {
        val items = listOf(
            StockItem("a", "Dal", foodId = "dal", unit = "g", countQty = 100.0, countedAt = t0, lowAt = 250.0),
            StockItem("c", "Jeera", unit = "g", tracked = false, status = "out"),
        )
        val levels = lib.stockLevels(items, emptyList(), emptyList(), emptyList(), PlanState.EMPTY, today)
        val names = lib.shoppingList(levels, PlanState.EMPTY).map { it.name }
        assertTrue("Dal" in names && "Jeera" in names)
    }
}
