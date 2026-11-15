package app.shoku

import app.shoku.data.backup.LegacyBackup
import app.shoku.data.db.RefType
import app.shoku.domain.Library
import app.shoku.domain.Recalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class RecalcTest {

    private val dataDir = File("../../shoku_streamlit/data")

    private fun load() = LegacyBackup.parse(dataDir.listFiles { f -> f.name.endsWith(".csv") }!!.associate { it.name to it.readText() })

    @Test
    fun unchangedLibraryChangesNothing() {
        assumeTrue(dataDir.isDirectory)
        val d = load()
        val lib = Library(d.foods, d.dishes, d.dishIngredients, d.batches, d.batchIngredients)
        val r = Recalc.affected(lib, d.dishIngredients, d.batchIngredients, d.logs, changedFoods = d.foods.map { it.id }.toSet())
        assertEquals("log entries changed", 0, r.logs.size)
        // Batch totals are recomputed but must equal what's stored (within rounding).
        r.batches.forEach { nb ->
            val old = d.batches.first { it.id == nb.id }
            assertTrue("${nb.dishName} kcal", abs((nb.totalKcal ?: 0.0) - (old.totalKcal ?: 0.0)) < 0.05)
        }
    }

    @Test
    fun editingAFoodFlowsIntoBatchesAndEntries() {
        assumeTrue(dataDir.isDirectory)
        val d = load()
        val rice = d.foods.first { it.name == "Bon-Ri Basmati Reis" }
        val doubled = d.foods.map { if (it.id == rice.id) it.copy(kcal = it.kcal!! * 2) else it }
        val lib = Library(doubled, d.dishes, d.dishIngredients, d.batches, d.batchIngredients)
        val r = Recalc.affected(lib, d.dishIngredients, d.batchIngredients, d.logs, changedFoods = setOf(rice.id))

        val batchesWithRice = d.batchIngredients.filter { it.refType == RefType.FOOD && it.refId == rice.id }.map { it.batchId }.toSet()
        assertTrue(batchesWithRice.isNotEmpty())
        assertEquals(batchesWithRice, r.batches.map { it.id }.toSet())
        r.batches.forEach { nb -> assertTrue(nb.totalKcal!! > d.batches.first { it.id == nb.id }.totalKcal!!) }

        val changedIds = r.logs.map { it.id }.toSet()
        val expected = d.logs.filter {
            (it.refType == RefType.FOOD && it.refId == rice.id) || (it.refType == RefType.BATCH && it.refId in batchesWithRice)
        }.map { it.id }.toSet()
        assertTrue("every rice and rice-batch entry is updated", changedIds.containsAll(expected))
        println("rice edit: ${r.batches.size} batches, ${r.logs.size} entries updated")
    }
}
