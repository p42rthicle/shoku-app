package app.shoku

import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.PlanSlot
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Library
import app.shoku.domain.PlanState
import app.shoku.domain.shoppingList
import app.shoku.domain.stillHidden
import app.shoku.domain.stockChangedAt
import app.shoku.domain.stockLevels
import app.shoku.domain.weekShopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** To buy and For next week follow the stock: adding stock updates or clears them; hidden rows return on a stock change. */
class StockListsTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val monday = LocalDate.of(2026, 11, 23)
    private val wrap = Food("wrap", "High-protein wrap", "g", 100.0, 318.0, 18.0, 6.0)
    private val seeds = Food("seeds", "Sunflower seeds", "g", 100.0, 573.0, 20.0, 9.0)
    private val lib = Library(listOf(wrap, seeds), emptyList(), emptyList(), emptyList(), emptyList(), listOf(FoodPortion("p", "wrap", "pc", 40.0)))
    // Breakfast every day: 2 wraps and 5 g of seeds.
    private val plan = PlanState(
        listOf(MealTemplate("t", "Wraps", "Breakfast")),
        listOf(TemplateItem("i1", "t", "food", "wrap", unit = "pc", qty = 2.0), TemplateItem("i2", "t", "food", "seeds", unit = "g", qty = 5.0, position = 1)),
        (1..7).map { PlanSlot("s$it", it, "Breakfast", "t") },
    )
    private fun levels(items: List<StockItem>, moves: List<StockMove> = emptyList()) = lib.stockLevels(items, moves, emptyList(), emptyList(), plan, today)

    @Test fun addingStockUpdatesBothLists() {
        // Nothing at home: both foods to buy, and the whole week (14 wraps, 35 g -> 50 g seeds).
        val none = levels(emptyList())
        assertEquals(setOf("High-protein wrap", "Sunflower seeds"), lib.shoppingList(none, plan).map { it.name }.toSet())
        val week0 = lib.weekShopping(plan, none, monday).associateBy { it.foodId }
        assertEquals(14.0, week0.getValue("wrap").buyRounded, 0.0)
        assertEquals("pc", week0.getValue("wrap").buyUnit)

        // 10 wraps counted in pieces (the food is in grams) and 100 g of seeds.
        val stocked = levels(listOf(
            StockItem("w", "High-protein wrap", foodId = "wrap", unit = "pc", countQty = 10.0, countedAt = 1),
            StockItem("s", "Sunflower seeds", foodId = "seeds", unit = "g", countQty = 100.0, countedAt = 1),
        ))
        assertTrue(lib.shoppingList(stocked, plan).none { it.reason == "In your plan, none at home" })
        val week1 = lib.weekShopping(plan, stocked, monday).associateBy { it.foodId }
        assertEquals(4.0, week1.getValue("wrap").buyRounded, 0.0) // 14 needed, 10 at home
        assertFalse("seeds" in week1) // 100 g covers 35 g
    }

    @Test fun hiddenRowsComeBackWhenStockChanges() {
        val item = StockItem("w", "High-protein wrap", foodId = "wrap", unit = "pc", countQty = 10.0, countedAt = 1_000, updatedAt = 1_000)
        val hiddenAt = 5_000L
        // Eating (a negative move) doesn't bring it back; buying more does.
        val eaten = lib.stockChangedAt(listOf(item), listOf(StockMove("m1", "w", -2.0, at = 6_000)))
        assertTrue(stillHidden(hiddenAt, eaten["item:w"]))
        assertTrue(stillHidden(hiddenAt, eaten["food:wrap"]))
        val bought = lib.stockChangedAt(listOf(item), listOf(StockMove("m2", "w", 10.0, at = 7_000)))
        assertFalse(stillHidden(hiddenAt, bought["item:w"]))
        // So does recounting it.
        val recounted = lib.stockChangedAt(listOf(item.copy(countQty = 3.0, countedAt = 8_000, updatedAt = 8_000)), emptyList())
        assertFalse(stillHidden(hiddenAt, recounted["food:wrap"]))
        // Nothing in stock for it at all: stays hidden.
        assertTrue(stillHidden(hiddenAt, null))
        assertFalse(stillHidden(null, null))
    }
}
