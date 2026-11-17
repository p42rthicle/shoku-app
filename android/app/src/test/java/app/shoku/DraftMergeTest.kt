package app.shoku

import app.shoku.data.DraftMerge
import app.shoku.data.FoodDraft
import app.shoku.data.db.Food
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A barcode's draft, then a label photo: the barcode keeps name and brand, the label fills and offers the numbers. */
class DraftMergeTest {
    private val scanned = FoodDraft(
        Food("s", "Paprika Chips", "g", 100.0, kcal = null, protein = 6.0, fibre = null, brand = "funny-frisch", barcode = "4000522105456"),
        emptyList(), found = false,
    )
    private val label = FoodDraft(
        Food("l", "Chips Paprika Style", "g", 100.0, kcal = 532.0, protein = 6.1, fibre = 4.2, carbs = 50.0, fat = 33.0, barcode = "4000522105456"),
        emptyList(), found = true, fromLabel = true,
    )

    @Test fun barcodeNameLabelNumbers() {
        val m = DraftMerge.merge(scanned, label)
        assertEquals("Paprika Chips", m.prefill.name) // the barcode's name stays
        assertEquals("funny-frisch", m.prefill.brand) // the label had none: the barcode's
        assertEquals(532.0, m.prefill.kcal!!, 0.0) // missing on the barcode: from the label
        assertEquals(4.2, m.prefill.fibre!!, 0.0)
        assertEquals("4000522105456", m.prefill.barcode)
        // Only the name truly differs (6.0 vs 6.1 g protein is the same reading).
        assertEquals(listOf("name"), m.choices.map { it.field })
        assertTrue(!m.choices.single().fromPhoto)
    }

    @Test fun eachChoiceSwitches() {
        val m = DraftMerge.merge(scanned.copy(prefill = scanned.prefill.copy(kcal = 480.0)), label)
        assertEquals(setOf("name", "kcal"), m.choices.map { it.field }.toSet())
        assertEquals(532.0, m.prefill.kcal!!, 0.0) // numbers: the label by default
        val back = DraftMerge.choose(m, "kcal", fromPhoto = false)
        assertEquals(480.0, back.prefill.kcal!!, 0.0)
        val named = DraftMerge.choose(back, "name", fromPhoto = true)
        assertEquals("Chips Paprika Style", named.prefill.name)
        assertEquals(480.0, named.prefill.kcal!!, 0.0) // the other choice stays
    }
}
