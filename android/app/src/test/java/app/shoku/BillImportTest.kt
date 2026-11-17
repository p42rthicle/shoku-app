package app.shoku

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.BillImport
import app.shoku.data.HomeRepository
import app.shoku.data.ShokuRepository
import app.shoku.data.ai.AiBillItem
import app.shoku.data.ai.AiBillResult
import app.shoku.data.ai.AiClient
import app.shoku.data.ai.AiEstimate
import app.shoku.data.db.Food
import app.shoku.data.db.LogEntry
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.StockItem
import app.shoku.data.reference.ScannedProduct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A read bill into stock against a real (in-memory) database, and every action of its review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BillImportTest {
    private lateinit var db: ShokuDatabase
    private lateinit var repo: ShokuRepository
    private lateinit var home: HomeRepository
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val milk = Food("m1", "Low-fat milk", "ml", 100.0, 47.0, 3.4, 0.0, brand = "Milbona")
    private val tomato = Food("t1", "Tomato", "g", 100.0, 18.0, 0.9, 1.2)

    private val chickpeasOff = ScannedProduct("4056489000001", "Kichererbsen", "Freshona", "g", 120.0, 7.0, 5.0, 15.0, 2.0, emptyList(), packQty = 400.0)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ShokuDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        repo = ShokuRepository(db, scope)
        home = HomeRepository(db, scope)
        db.library().upsertFoods(listOf(milk, tomato))
        context.getSharedPreferences("bills", 0).edit().clear().commit()
        BillImport.init(context)
    }

    @After fun tearDown() = db.close()

    private suspend fun read(vararg items: AiBillItem, skipped: List<String> = emptyList()): BillImport.Bill {
        val lib = repo.library.first()
        val keys = AiClient().libraryKeys(lib)
        val deps = BillImport.Deps(repo, home, reference = null, offSearch = { q -> chickpeasOff.takeIf { "Kichererbsen" in q } }, pause = {})
        return BillImport.add(deps, lib, keys, AiBillResult(items.toList(), skipped)).also { BillImport.keep(it) }
    }

    private fun key(lib: app.shoku.domain.Library, id: String) = AiClient().libraryKeys(lib).refs.entries.first { it.value.second == id }.key

    private val pepper = AiBillItem("Paprika rot 0,172 kg x 4,29", name = "Red bell pepper", unit = "g", qty = 172.0,
        estimate = AiEstimate(31.0, 1.0, 2.1, 6.0, 0.3, 172.0), packaged = false, search = "Paprika rot", pieceGrams = 150.0)
    private val chickpeas = AiBillItem("Kichererbsen 0,59 x 3", name = "Chickpeas, tinned", unit = "g", qty = 1200.0,
        estimate = AiEstimate(120.0, 7.0, 5.0, 15.0, 2.0, 1200.0), packs = 3.0, packaged = true, search = "Kichererbsen")

    private suspend fun stockOf(foodId: String) = home.stockFor(foodId)

    @Test fun addsYourFoodsAndNewOnes() = runBlocking {
        val lib = repo.library.first()
        val bill = read(
            AiBillItem("H-Milch 1,5% 0,85 x 2", matchKey = key(lib, "m1"), name = "Low-fat milk", unit = "ml", qty = 2000.0, packs = 2.0),
            pepper, chickpeas, skipped = listOf("Duschgel"),
        )
        assertEquals(3, bill.lines.size)
        assertEquals(listOf("Duschgel"), bill.skipped)
        // Your milk: straight onto its stock, nothing to check.
        val m = bill.lines.single { it.foodId == "m1" }
        assertEquals("yours", m.source)
        assertEquals(2000.0, stockOf("m1")!!.countQty!!, 0.0)
        // The pepper: a new food from the estimate, countable in pieces, its stock added.
        val p = bill.lines.single { it.said.startsWith("Paprika") }
        assertEquals("estimate", p.source)
        assertEquals(172.0, p.qty, 0.0)
        assertEquals(150.0, db.library().allPortions().single { it.foodId == p.foodId && it.label == "pc" }.qty, 0.0)
        assertEquals(172.0, stockOf(p.foodId)!!.countQty!!, 0.0)
        // Chickpeas: Open Food Facts' product, 3 cans of its 400 g.
        val c = bill.lines.single { it.said.startsWith("Kicher") }
        assertEquals("off", c.source)
        assertEquals(1200.0, c.qty, 0.0)
        assertEquals("Freshona", repo.library.first().foods.getValue(c.foodId).brand)
        // Two to check (the milk isn't).
        assertEquals(2, BillImport.pending())
    }

    @Test fun looksRightAndAllLookRight() = runBlocking {
        val bill = read(pepper, chickpeas)
        BillImport.looksRight(bill.lines.first())
        assertEquals(1, BillImport.pending())
        BillImport.allLookRight()
        assertEquals(0, BillImport.pending())
    }

    @Test fun removeTakesItBackOut() = runBlocking {
        // Milk already at home (500 ml): removing the bill's milk takes only the bill's 2 l back off.
        home.addFoodStock(milk, 500.0)
        val lib = repo.library.first()
        val bill = read(AiBillItem("H-Milch", matchKey = key(lib, "m1"), name = "Low-fat milk", unit = "ml", qty = 2000.0), pepper)
        val milkLine = bill.lines.single { it.foodId == "m1" }
        BillImport.remove(repo, home, milkLine)
        val moves = db.home().allMoves().filter { !it.deleted && it.itemId == stockOf("m1")!!.id }
        assertEquals(0.0, moves.sumOf { it.delta }, 0.0) // +2000 bought, -2000 taken back
        // A new food the bill made, unused: its stock and the food go.
        val pepperLine = bill.lines.single { it.said.startsWith("Paprika") }
        BillImport.remove(repo, home, pepperLine)
        assertNull(stockOf(pepperLine.foodId))
        assertNull(repo.library.first().foods[pepperLine.foodId])
        assertTrue(BillImport.bills.value.first().lines.none { it.id == pepperLine.id })
    }

    @Test fun removeKeepsAFoodYouLogged() = runBlocking {
        val bill = read(pepper)
        val line = bill.lines.single()
        db.logs().upsertAll(listOf(LogEntry("e1", "2026-11-17", "Lunch", "food", line.foodId, "Red bell pepper", "g", 50.0, 15.0, 0.5, 1.0)))
        BillImport.remove(repo, home, line)
        assertNull(stockOf(line.foodId))
        assertTrue(repo.library.first().foods.containsKey(line.foodId)) // logged, so kept
    }

    @Test fun replacedMovesToTheChosenFood() = runBlocking {
        val bill = read(pepper)
        val line = bill.lines.single()
        // Replace with their tomato (its stock was added by the amount sheet).
        home.addFoodStock(tomato, 172.0)
        BillImport.replaced(repo, home, line, tomato, 172.0)
        assertNull(stockOf(line.foodId))
        assertNull(repo.library.first().foods[line.foodId])
        val now = BillImport.bills.value.first().lines.single()
        assertEquals("t1", now.foodId)
        assertEquals("yours", now.source)
        assertTrue(now.reviewed)
        assertEquals(0, BillImport.pending())
    }

    @Test fun sayItLinesShowForReview() {
        BillImport.record(listOf(BillImport.Line("x", "2 kg dragonfruit", "Dragon fruit", "f9", 2000.0, "g", "estimate", created = true, newStock = true)))
        assertEquals(1, BillImport.pending())
    }

    @Test fun freshVegetablesAreCountedNotSpices() {
        fun place(name: String) = home.placeFor(Food("x", name, "g", 100.0, 20.0, 1.0, 1.0))
        assertEquals("Fridge", place("Red bell pepper"))
        assertEquals("Fridge", place("Fresh coriander"))
        assertEquals("Fridge", place("Green chilli"))
        assertEquals("Spices", place("Chilli flakes"))
        assertEquals("Spices", place("Black pepper"))
        assertEquals("Spices", place("Paprika powder"))
        assertEquals("Spices", place("Chicken masala"))
    }

    @Test fun repairCountsAWronglyStatusOnlyItem() = runBlocking {
        val bill = read(pepper)
        val line = bill.lines.single()
        // As 0.9.55 left it: status only, and moved to the fridge by hand.
        val item = home.stockFor(line.foodId)!!
        home.saveStockItem(item.copy(place = "Fridge", tracked = false, countQty = null))
        BillImport.repairPlaces(repo, home)
        val fixed = home.stockFor(line.foodId)!!
        assertTrue(fixed.tracked)
        assertEquals(172.0, fixed.countQty!!, 0.0)
        assertEquals("Fridge", fixed.place)
    }

    @Test fun removingAReplacedLineTakesOnlyItsAmount() = runBlocking {
        home.addFoodStock(tomato, 500.0) // tomatoes already at home
        val line = read(pepper).lines.single()
        home.addFoodStock(tomato, 172.0)
        BillImport.replaced(repo, home, line, tomato, 172.0)
        BillImport.remove(repo, home, BillImport.bills.value.first().lines.single())
        val item = stockOf("t1")!!
        val moves = db.home().allMoves().filter { !it.deleted && it.itemId == item.id }.sumOf { it.delta }
        assertEquals(500.0, item.countQty!! + moves, 0.0) // the 500 g stay
    }
}
