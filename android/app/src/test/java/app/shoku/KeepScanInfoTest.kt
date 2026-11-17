package app.shoku

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.DraftMerge
import app.shoku.data.FoodDraft
import app.shoku.data.FoodLookup
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.reference.ScannedProduct
import app.shoku.data.reference.toLibraryFood
import app.shoku.domain.Library
import app.shoku.ui.plan.packStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whatever a scan, label or pack photo found stays with the food: its pack, its serving, its other name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class KeepScanInfoTest {
    private val muesli = ScannedProduct("4000000000001", "Knusper Müsli", "Testhof", "g", 450.0, 10.0, 8.0, 60.0, 15.0, emptyList(), servingQty = 45.0, packQty = 500.0)

    @Test fun anOnlineProductKeepsItsServingAndPack() {
        val (food, portions) = muesli.toLibraryFood()
        assertEquals(mapOf("serving" to 45.0, "pack" to 500.0), portions.associate { it.label to it.qty })
        // The same product picked again gives the same units (no duplicates).
        assertEquals(portions.map { it.id }, muesli.toLibraryFood().second.map { it.id })
        assertTrue(portions.all { it.foodId == food.id })
    }

    @Test fun aPackTheSizeOfOneServingIsNotRepeated() {
        val bar = Food("b", "Protein bar", "g", 100.0, 350.0, 30.0, 5.0)
        assertTrue(FoodLookup.packPortion(bar, 50.0, serving = 50.0).isEmpty())
        assertEquals(20.0, FoodLookup.packPortion(bar, 20.0).single().qty, 0.0)
    }

    @Test fun theNameNotChosenStaysAsAnAlias() {
        val barcode = FoodDraft(Food("x", "Nutella", "g", 100.0, 539.0, 6.3, null, brand = "Ferrero", barcode = "3017620422003"), emptyList(), found = true)
        val labelFood = Food("y", "Hazelnut spread with cocoa", "g", 100.0, 539.0, 6.3, 3.0, brand = "Ferrero")
        val label = FoodDraft(labelFood, emptyList(), found = true, fromLabel = true, portions = FoodLookup.packPortion(labelFood, 400.0), packQty = 400.0)
        val merged = DraftMerge.merge(barcode, label)
        assertEquals("Nutella", merged.prefill.name)
        assertEquals("Hazelnut spread with cocoa", merged.prefill.aliases)
        assertEquals(400.0, merged.portions.single { it.label == "pack" }.qty, 0.0)
        assertEquals(400.0, merged.packQty!!, 0.0)
        // Aliases don't repeat or echo the name.
        assertEquals("a, Nutella", FoodLookup.withAlias("a", "Nutella", "Hazelnut spread"))
        assertEquals("a, Nutella", FoodLookup.withAlias("a, Nutella", "nutella", "Hazelnut spread"))
        assertEquals("", FoodLookup.withAlias("", "Hazelnut Spread", "Hazelnut spread"))
    }

    @Test fun usingAnExistingFoodKeepsWhatTheScanFound() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, ShokuDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = ShokuRepository(db, CoroutineScope(Dispatchers.Unconfined))
            val mine = Food("h1", "Hazelnut chocolate spread", "g", 100.0, 539.0, 6.3, 3.0, brand = "Nutella")
            db.library().upsertFoods(listOf(mine))
            repo.savePortions(listOf(FoodPortion("p1", "h1", "tbsp", 15.0)))
            val scanned = Food("s", "Nutella", "g", 100.0, 539.0, 6.3, null, brand = "Ferrero", barcode = "3017620422003")
            val draft = FoodDraft(scanned, emptyList(), found = true, portions = FoodLookup.servingPortion(scanned, 15.0), packQty = 450.0)
            val used = FoodLookup.useExisting(repo, mine, draft)
            assertEquals("3017620422003", used.barcode)
            assertEquals("Nutella", used.aliases)
            val units = repo.library.first { it.portionsOf(mine).any { p -> p.label == "pack" } }.portionsOf(mine).associate { it.label to it.qty }
            assertEquals(mapOf("tbsp" to 15.0, "serving" to 15.0, "pack" to 450.0), units)
        } finally { db.close() }
    }

    @Test fun stockStartsInWholePacks() {
        val masala = Food("m", "Aloo Sabzi Masala", "g", 100.0, 300.0, 10.0, 20.0, brand = "SDS")
        val lib = Library(listOf(masala), emptyList(), emptyList(), emptyList(), emptyList(), listOf(FoodPortion("p", "m", "pack", 30.0)))
        assertEquals("pack" to 1.0, packStart(lib, masala, 30.0))
        assertEquals("pack" to 2.0, packStart(lib, masala, 60.0))
        assertEquals("g" to 45.0, packStart(lib, masala, 45.0))
        val plain = Library(listOf(masala), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals("g" to 100.0, packStart(plain, masala, 100.0))
    }
}
