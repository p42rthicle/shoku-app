package app.shoku

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.HomeRepository
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Food
import app.shoku.data.db.LogEntry
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockPlaces
import app.shoku.domain.PlanState
import app.shoku.domain.stockLevels
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
import java.time.LocalDate

/** Spices are counted like everything else: "40 g left", low at a fifth, used up spoon by spoon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SpiceStockTest {
    private lateinit var db: ShokuDatabase
    private lateinit var repo: ShokuRepository
    private lateinit var home: HomeRepository
    private val masala = Food("pb", "Pav Bhaji Masala", "g", 100.0, 300.0, 12.0, 20.0, brand = "Everest")

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, ShokuDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        repo = ShokuRepository(db, scope)
        home = HomeRepository(db, scope)
        db.library().upsertFoods(listOf(masala))
    }

    @After fun tearDown() = db.close()

    @Test fun aSpiceKeepsItsAmountAndGoesDownAsItIsUsed() = runBlocking {
        home.addFoodStock(masala, 100.0, StockPlaces.SPICES)
        val item = home.stockFor("pb")!!
        assertTrue(item.tracked)
        assertEquals(100.0, item.countQty!!, 0.0)
        assertEquals(20.0, item.lowAt!!, 0.0) // low at a fifth
        val lib = repo.library.first()
        assertEquals(2.5, lib.portionsOf(masala).first { it.label == "tsp" }.qty, 0.0)
        // Two teaspoons in a meal: 5 g less.
        val eaten = LogEntry("e1", LocalDate.now().toString(), "Dinner", "food", "pb", "Pav Bhaji Masala", "tsp", 2.0, 15.0, 0.6, 1.0,
            updatedAt = item.countedAt + 1000)
        val level = lib.stockLevels(listOf(item), emptyList(), listOf(eaten), emptyList(), PlanState.EMPTY, LocalDate.now()).single()
        assertEquals(95.0, level.qty!!, 0.01)
    }

    @Test fun oldHaveLowOutSpicesAskForTheirAmount() = runBlocking {
        db.home().upsertStock(listOf(
            StockItem("s1", "Pav Bhaji Masala", foodId = "pb", unit = "g", place = StockPlaces.SPICES, tracked = false, status = "have"),
            StockItem("s2", "Hing", unit = "g", place = StockPlaces.SPICES, tracked = false, status = "out"),
        ))
        home.countStatusItems()
        val rows = db.home().allStock().associateBy { it.id }
        assertTrue(rows.getValue("s1").tracked)
        assertNull(rows.getValue("s1").countQty) // "Amount not set · tap to add"
        assertEquals(0.0, rows.getValue("s2").countQty!!, 0.0) // out is 0
        assertTrue(repo.library.first().portionsOf(masala).any { it.label == "tsp" })
        // Buying it later: that's how much there is.
        home.addFoodStock(masala, 100.0)
        assertEquals(100.0, home.stockFor("pb")!!.countQty!!, 0.0)
    }
}
