package app.shoku

import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.LogEntry
import app.shoku.domain.Library
import app.shoku.domain.carryAmount
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaceTest {
    private val nutella = Food("n", "Hazelnut spread", "g", 100.0, 539.0, 6.3, 3.4)
    private val lib = Library(listOf(nutella), emptyList(), emptyList(), emptyList(), emptyList(), listOf(FoodPortion("p", "n", "tbsp", 15.0)))
    private fun entry(unit: String, qty: Double, refType: String = "quick", refId: String? = null) =
        LogEntry("e", "2026-11-14", "Lunch", refType, refId, "Muesli (estimate)", unit, qty, 139.0, 3.3, 2.0)

    @Test
    fun keepsTheSameUnitWhenPossible() = assertEquals("g" to 33.0, carryAmount(entry("g", 33.0), lib, listOf("serving", "g")))

    @Test
    fun convertsPortionsToGrams() = assertEquals("g" to 30.0, carryAmount(entry("tbsp", 2.0, "food", "n"), lib, listOf("pc", "g")))

    @Test
    fun fallsBackToOneOfTheFirstUnit() = assertEquals("serving" to 1.0, carryAmount(entry("pc", 2.0), lib, listOf("serving")))
}
