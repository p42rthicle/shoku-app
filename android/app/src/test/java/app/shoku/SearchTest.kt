package app.shoku

import app.shoku.domain.amountFor
import app.shoku.domain.matchScore
import app.shoku.domain.parseQuery
import app.shoku.domain.suggestFamilies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun parsesAmountsInAnyOrder() {
        parseQuery("nutella 4 tbsp").let { assertEquals("nutella", it.text); assertEquals(4.0, it.qty); assertEquals("tbsp", it.unit) }
        parseQuery("4 tbsp nutella").let { assertEquals("nutella", it.text); assertEquals("tbsp", it.unit) }
        parseQuery("200g skyr").let { assertEquals("skyr", it.text); assertEquals(200.0, it.qty); assertEquals("g", it.unit) }
        parseQuery("2 slices of toast").let { assertEquals("toast", it.text); assertEquals(2.0, it.qty); assertEquals("slice", it.unit) }
        parseQuery("0.5 l milk").let { assertEquals("milk", it.text); assertEquals(500.0, it.qty); assertEquals("ml", it.unit) }
        parseQuery("2 eggs").let { assertEquals("eggs", it.text); assertEquals(2.0, it.qty); assertNull(it.unit) }
        parseQuery("½ banana").let { assertEquals("banana", it.text); assertEquals(0.5, it.qty) }
        parseQuery("nutella").let { assertEquals("nutella", it.text); assertNull(it.qty) }
        assertTrue(parseQuery("2 toast and an egg").multiple)
    }

    @Test
    fun amountsFitTheFoodsUnits() {
        assertEquals("tbsp" to 4.0, amountFor(listOf("tsp", "tbsp", "g"), parseQuery("nutella 4 tbsp")))
        assertNull(amountFor(listOf("g"), parseQuery("nutella 4 tbsp")))
        assertEquals("medium" to 2.0, amountFor(listOf("medium", "large", "g"), parseQuery("2 bananas")))
        assertEquals("g" to 200.0, amountFor(listOf("cup", "g"), parseQuery("skyr 200")))
        assertEquals("bowl" to 1.0, amountFor(listOf("bowl", "g"), parseQuery("1 katori dal")))
    }

    @Test
    fun ranksAndToleratesTypos() {
        assertTrue(matchScore("nutella", "hazelnut chocolate spread", "nutella") > 0)
        assertTrue(matchScore("oreao", "oreo", "") > 0)
        assertTrue(matchScore("eggs", "egg, medium", "") > 0)
        assertEquals(0, matchScore("pizza", "whole milk", "milbona"))
        assertTrue(matchScore("butter", "butter", "milsani") > matchScore("butter", "butter biscuit", "yadav"))
    }

    @Test
    fun pluralsMatchTheFoodItself() {
        assertTrue(matchScore("pumpkin seeds", "pumpkin seed", "kurbiskern") > matchScore("pumpkin seeds", "rye roll with pumpkin seeds", ""))
        assertTrue(matchScore("tomatoes", "tomato", "") > 0)
    }

    @Test
    fun familySuggestionsUseWholeWords() {
        fun generic(id: String, name: String, aliases: String) =
            app.shoku.data.db.Food(id, name, "g", 100.0, 100.0, 1.0, 0.0, kind = app.shoku.data.db.FoodKind.GENERIC, aliases = aliases)
        val lib = app.shoku.domain.Library(
            listOf(generic("t", "Toast bread (slice)", "Toastbrot, Sandwich toast"), generic("m", "Whole milk", "Vollmilch"), generic("c", "Crisps", "Chips, potato chips")),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val names = lib.suggestFamilies("Vollkorntoast sliced breads wholemeal sliced breads").map { it.name }
        assertEquals(listOf("Toast bread (slice)"), names)
    }
}
