package app.shoku

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.LogEntry
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Duplicates
import app.shoku.domain.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole merge against a real (in-memory) database: every table that points at a food. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MergeFoodsTest {
    private lateinit var db: ShokuDatabase
    private lateinit var repo: ShokuRepository

    // Logged 27x per piece (no fibre, an old wrong "1 pc = 60 g" unit) / read from the label per 100 g, in stock.
    private val wrapPc = Food("w1", "High-protein wrap", "pc", 1.0, 127.0, 7.4, 2.0, brand = "Goldähren", source = "import")
    private val wrapLabel = Food("w2", "Aldi Goldähren PROTEIN WRAPS", "g", 100.0, 318.0, 18.4, null, 40.0, 8.0, brand = "Goldähren", barcode = "4061458123456", source = "label")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ShokuDatabase::class.java).allowMainThreadQueries().build()
        repo = ShokuRepository(db, CoroutineScope(Dispatchers.Unconfined))
        val lib = db.library()
        lib.upsertFoods(listOf(wrapPc, wrapLabel))
        lib.upsertPortions(listOf(FoodPortion("p1", "w1", "g", 1 / 60.0), FoodPortion("p2", "w2", "wrap", 40.0)))
        db.logs().upsertAll((1..27).map { LogEntry("e$it", "2026-10-%02d".format(it), "Dinner", "food", "w1", "High-protein wrap", "pc", 1.0, 127.0, 7.4, null) } +
            LogEntry("e-label", "2026-11-16", "Lunch", "food", "w2", "Protein Wraps", "g", 40.0, 127.2, 7.4, null))
        lib.upsertDishes(listOf(Dish("d1", "Paneer wrap", fixed = false, servings = 1.0)))
        lib.upsertDishIngredients(listOf(DishIngredient("di1", "d1", "food", "w2", "g", 40.0, 0)))
        lib.upsertBatches(listOf(Batch("b1", null, "Wrap rolls", "2026-11-10", 4.0, totalKcal = 600.0, totalProtein = 40.0, totalFibre = 4.0)))
        lib.upsertBatchIngredients(listOf(BatchIngredient("bi1", "b1", "food", "w1", "pc", 4.0, 0)))
        val home = db.home()
        home.upsertTemplates(listOf(MealTemplate("t1", "Wrap dinner", "Dinner")))
        home.upsertTemplateItems(listOf(TemplateItem("ti1", "t1", "food", "w1", unit = "pc", qty = 1.0)))
        // Stock on both copies: 1520 g of label wraps and 2 pieces on the old one.
        home.upsertStock(listOf(
            StockItem("s1", "Protein Wraps", foodId = "w2", unit = "g", countQty = 1520.0, countedAt = 1L),
            StockItem("s2", "High-protein wrap", foodId = "w1", unit = "pc", countQty = 2.0, countedAt = 2L),
        ))
        home.upsertMoves(listOf(StockMove("m1", "s2", 1.0, 3L)))
    }

    @After fun tearDown() = db.close()

    private suspend fun library() = Library(db.library().allFoods().filter { !it.deleted }, db.library().allDishes(), db.library().allDishIngredients(),
        db.library().allBatches(), db.library().allBatchIngredients(), db.library().allPortions())

    @Test fun mergeKeepsHistoryAndMovesEverything() = runBlocking {
        val before = db.logs().allIncludingDeleted().filter { !it.deleted }
        val group = Duplicates.find(library(), before, setOf("w2")).single()
        assertEquals("w1", group.keep.food.id)
        assertEquals("w2", group.valuesFrom.food.id)
        val plan = Duplicates.plan(library(), group)
        repo.mergeFoods(plan)

        val foods = db.library().allFoods()
        val kept = foods.single { !it.deleted }
        // One food: the clean name (not "Aldi Goldähren PROTEIN WRAPS"), the brand, the label's numbers and barcode.
        assertEquals("w1", kept.id)
        assertEquals("High-protein wrap", kept.name)
        assertEquals("Goldähren", kept.brand)
        assertEquals("g", kept.unit)
        assertEquals(318.0, kept.kcal!!, 0.01)
        assertEquals(40.0, kept.carbs!!, 0.01)
        assertEquals(8.0, kept.fat!!, 0.01)
        // Fibre only the per-piece copy had: 2 g per 39.9 g piece = 5 g per 100 g.
        assertEquals(5.0, kept.fibre!!, 0.05)
        assertEquals("4061458123456", kept.barcode)
        assertTrue("Aldi Goldähren PROTEIN WRAPS" in kept.aliases)
        assertTrue(foods.single { it.id == "w2" }.deleted)

        // Units: 1 pc = 39.9 g by calories; the label's 40 g "wrap" isn't a second unit; the wrong 60 g one is gone.
        val units = db.library().allPortions().filter { !it.deleted && it.foodId == "w1" }.associate { it.label to it.qty }
        assertEquals(39.9, units["pc"]!!, 0.05)
        assertFalse("wrap" in units) // the same amount as "pc": one unit, not two
        assertFalse("g" in units)

        // History: every entry still there with its numbers and unit, all on the one food.
        val after = db.logs().allIncludingDeleted().filter { !it.deleted }
        assertEquals(before.size, after.size)
        assertEquals(before.sumOf { it.kcal!! }, after.sumOf { it.kcal!! }, 0.001)
        assertEquals(before.map { it.id to it.unit }.toSet(), after.map { it.id to it.unit }.toSet())
        assertTrue(after.all { it.refId == "w1" })
        // The kept food can still work out a past "1 pc" entry: ~127 kcal.
        assertEquals(127.0, library().entryNutrients("food", "w1", "pc", 1.0)!!.kcal!!, 0.5)

        // Recipes, batches and the meal plan follow.
        assertEquals("w1", db.library().allDishIngredients().single().refId)
        assertEquals("w1", db.library().allBatchIngredients().single().refId)
        assertEquals("w1", db.home().allTemplateItems().single().refId)
        assertEquals(600.0, db.library().allBatches().single().totalKcal!!, 0.001) // a cooked batch keeps its numbers

        // Stock: one row in grams; the 2 + 1 pieces were added to it as 119.8 g.
        val stock = db.home().allStock().filter { !it.deleted }
        assertEquals(1, stock.size)
        val s = stock.single()
        assertEquals("w1", s.foodId)
        assertEquals("g", s.unit)
        assertEquals(1520.0, s.countQty!!, 0.01)
        val added = db.home().allMoves().filter { !it.deleted && it.itemId == s.id }.sumOf { it.delta }
        assertEquals(3 * 39.94, added, 0.2)
        assertNotNull(db.home().allStock().single { it.id == "s2" }.takeIf { it.deleted })
    }

    @Test fun bestNameIsCleanAndKeepsTheBrandSeparate() {
        val a = Food("v1", "roasted vermicelli", "g", 100.0, null, null, null, brand = "Bambino", barcode = "8901242107408", archived = true)
        val b = Food("v2", "Bambino Roasted Vermicelli", "g", 100.0, 381.0, 12.0, 2.0, brand = "Bambino")
        val c = Food("v3", "Roasted Vermicelli", "g", 100.0, 381.0, 12.0, 2.0, 77.0, 1.5, brand = "Bambino", source = "label")
        assertEquals("Roasted Vermicelli", Duplicates.bestName(listOf(a, b, c), keep = c, valuesFrom = c))
        assertEquals("Roasted Vermicelli", Duplicates.bestName(listOf(a, b, c), keep = a, valuesFrom = b))
        val lib = Library(listOf(a, b, c), emptyList(), emptyList(), emptyList(), emptyList())
        val g = Duplicates.find(lib, emptyList(), emptySet()).single()
        val m = Duplicates.plan(lib, g)
        assertEquals("Roasted Vermicelli", m.food.name)
        assertEquals("Bambino", m.food.brand)
        assertEquals("8901242107408", m.food.barcode) // from the archived copy
        assertEquals(77.0, m.food.carbs!!, 0.01)
    }

    @Test fun onlineOrdersBecomePlainNamesWithANote() = runBlocking {
        db.library().upsertDishes(listOf(Dish("o1", "[o] Chicken Curry (Pak Resto.)", fixed = true, servings = 1.0), Dish("o2", "[o] Jeera Rice", fixed = true, servings = 1.0)))
        db.logs().upsertAll(listOf(
            LogEntry("x1", "2026-05-01", "Dinner", "dish", "o1", "[o] Chicken Curry (Pak Resto.)", "serving", 1.0, 450.0, 30.0, 2.0),
            LogEntry("x2", "2026-05-01", "Dinner", "dish", "o2", "[o] Jeera Rice", "serving", 1.0, 300.0, 6.0, 1.0, note = "extra raita"),
        ))
        repo.tidyOnlineOrders()
        val dishes = db.library().allDishes().associateBy { it.id }
        assertEquals("Chicken Curry", dishes["o1"]!!.name)
        assertEquals("Jeera Rice", dishes["o2"]!!.name)
        val e = db.logs().allIncludingDeleted().associateBy { it.id }
        assertEquals("Chicken Curry", e["x1"]!!.name)
        assertEquals("Ordered online · Pak Resto.", e["x1"]!!.note)
        assertEquals(450.0, e["x1"]!!.kcal!!, 0.001)
        assertEquals("extra raita", e["x2"]!!.note) // an existing note stays
        assertEquals("High-protein wrap", e["e1"]!!.name) // others untouched
    }
}
