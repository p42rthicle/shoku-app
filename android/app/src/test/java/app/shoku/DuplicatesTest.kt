package app.shoku

import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.LogEntry
import app.shoku.domain.Duplicates
import app.shoku.domain.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private val wrapPc = Food("w1", "High-protein wrap", "pc", 1.0, 127.0, 7.4, null, brand = "Goldähren", source = "import")
    private val wrapLabel = Food("w2", "Protein Wraps", "g", 100.0, 318.0, 18.4, 5.0, 40.0, 8.0, brand = "Goldähren", source = "label")
    private val musli = Food("m1", "Chocolate crunchy müsli", "g", 100.0, 447.0, 8.0, 5.0, brand = "Bio Knusper")
    private val musliBanana = Food("m2", "Chocolate banana crunchy müsli", "g", 100.0, 454.0, 8.0, 5.0, brand = "Bio Knusper")
    private val butter = Food("b1", "Butter", "g", 100.0, 744.0, 0.7, 0.0, kind = FoodKind.GENERIC)
    private val butterMilsani = Food("b2", "Butter", "g", 100.0, 744.0, 0.7, 0.0, brand = "Milsani", parentId = "b1")
    private val tomatoS = Food("t1", "Tomato (s)", "pc", 1.0, 17.0, 0.8, 1.0, parentId = "t0")
    private val tomatoM = Food("t2", "Tomato (m)", "pc", 1.0, 23.0, 1.0, 1.4, parentId = "t0")
    private val haldiram = Food("h1", "Milk cake", "g", 100.0, 433.0, 9.0, null, brand = "Haldiram's")
    private val haldiram2 = Food("h2", "Milk Cake", "g", 100.0, 430.0, 9.0, null, brand = "Haldirams Snacks Pvt. Ltd.")
    private val portions = listOf(FoodPortion("p1", "w2", "wrap", 40.0), FoodPortion("p2", "w1", "g", 1 / 60.0))
    private val lib = Library(listOf(wrapPc, wrapLabel, musli, musliBanana, butter, butterMilsani, tomatoS, tomatoM, haldiram, haldiram2), emptyList(), emptyList(), emptyList(), emptyList(), portions)
    private val logs = (1..5).map { LogEntry("e$it", "2026-11-0$it", "Lunch", "food", "w1", "High-protein wrap", "pc", 1.0, 127.0, 7.4, null) }

    @Test
    fun findsTheSameProductNotFlavoursFamiliesOrSizes() {
        val groups = Duplicates.find(lib, logs, emptySet())
        assertEquals(setOf(setOf("w1", "w2"), setOf("h1", "h2")), groups.map { g -> g.members.map { it.food.id }.toSet() }.toSet())
    }

    @Test
    fun keepsTheLoggedOneWithTheLabelsNumbersAndConvertsPieces() {
        val g = Duplicates.find(lib, logs, emptySet()).first { it.members.any { m -> m.food.id == "w1" } }
        assertEquals("w1", g.keep.food.id) // logged 5 times
        assertEquals("w2", g.valuesFrom.food.id) // from the label, full macros
        val m = Duplicates.plan(lib, g, now = 1L)
        assertEquals("High-protein wrap", m.food.name)
        assertEquals("g", m.food.unit)
        assertEquals(318.0, m.food.kcal!!, 0.01)
        // One piece is ~40 g by calories (127 / 3.18); the old "1 pc = 60 g" unit is dropped.
        assertEquals(39.9, m.portions.first { it.label == "pc" }.qty, 0.05)
        assertEquals(40.0, m.portions.first { it.label == "wrap" }.qty, 0.01)
        assertTrue(m.portions.none { it.label == "g" })
        assertTrue("Protein Wraps" in m.food.aliases)
        assertEquals(listOf("w2"), m.gone.map { it.id })
    }

    @Test
    fun aNewScanMatchesWhatsInTheLibrary() {
        val scanned = Food("new", "Protein wraps", "g", 100.0, 318.0, 18.4, null, brand = "Goldähren", barcode = "4061458000000")
        assertEquals(listOf("w2", "w1"), Duplicates.matches(lib, scanned).map { it.id }.take(2).sorted().reversed())
    }

    @Test
    fun aNewTomatoIsNotOfferedTheHiddenSizeRows() {
        val tomato = Food("t0", "Tomato", "g", 100.0, 19.0, 0.9, 1.2, kind = FoodKind.GENERIC)
        val small = Food("t1", "Tomato (s)", "pc", 1.0, 17.0, 0.8, 1.0, archived = true, parentId = "t0")
        val l = Library(listOf(tomato, small), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("t0"), Duplicates.matches(l, Food("new", "Tomato", "g", 100.0, 18.0, 0.9, 1.2, kind = FoodKind.GENERIC)).map { it.id })
    }
}
