package app.shoku

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.ShokuRepository
import app.shoku.data.db.LogEntry
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.StockItem
import app.shoku.domain.StockLevel
import app.shoku.ui.plan.StockView
import app.shoku.ui.plan.matching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StockAndCopyTest {
    private fun level(id: String, name: String, foodId: String) = StockLevel(StockItem(id, name, foodId = foodId, unit = "g", countQty = 100.0), 100.0, null, null)

    @Test fun stockSearchFindsABrand() {
        val view = StockView(
            levels = listOf(level("a", "Chicken masala", "f1"), level("b", "Aloo Sabzi Masala", "f2"), level("c", "Bhujia", "f3"), level("d", "Rice", "f4")),
            brands = mapOf("f1" to "SDS", "f2" to "SDS", "f3" to "Haldiram's"),
        )
        assertEquals(listOf("Chicken masala", "Aloo Sabzi Masala"), view.matching("sds").levels.map { it.item.name })
        assertEquals(listOf("Bhujia"), view.matching("Haldi Ram").levels.map { it.item.name }) // spoken with a space
        assertEquals(listOf("Rice"), view.matching("rice").levels.map { it.item.name }) // names still work
        assertEquals(4, view.matching("").levels.size)
    }

    @Test fun copyOnlyTheTickedItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, ShokuDatabase::class.java).allowMainThreadQueries().build()
        val repo = ShokuRepository(db, CoroutineScope(Dispatchers.Unconfined))
        db.logs().upsertAll(listOf("Rice" to "e1", "Yogurt" to "e2", "Seeds" to "e3").map { (n, id) -> LogEntry(id, "2026-11-16", "Breakfast", "food", "f-$n", n, "g", 50.0, 50.0, 1.0, null) })
        val copies = repo.copyMeal(LocalDate.parse("2026-11-16"), "Breakfast", LocalDate.parse("2026-11-23"), "Breakfast", "planned", only = setOf("e1", "e3"))
        assertEquals(listOf("Rice", "Seeds"), copies.map { it.name })
        assertEquals(setOf("planned"), copies.map { it.status }.toSet())
        db.close()
    }
}
