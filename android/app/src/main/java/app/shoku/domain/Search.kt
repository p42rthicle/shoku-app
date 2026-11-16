package app.shoku.domain

import app.shoku.data.db.Food
import java.text.Normalizer

/** Lowercase, umlauts and accents folded ("Müsli" -> "musli", "Öl" -> "ol"), ß -> ss. */
/** Compiled once (searchKey runs for every row of every search). */
private val MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")

fun searchKey(s: String): String =
    Normalizer.normalize(s.lowercase().replace("ß", "ss"), Normalizer.Form.NFD).replace(MARKS, "")

/** Everything a food can be found by: its name, brand, aliases, and its generic's name and aliases. */
fun Library.searchText(f: Food): String {
    val parent = f.parentId?.let { foods[it] }
    return searchKey(listOfNotNull(f.name, f.brand, f.aliases, parent?.name, parent?.aliases).joinToString(" "))
}

/** True when every search word appears somewhere in [text] (already a searchKey). */
fun matchesAll(text: String, query: String): Boolean {
    val words = searchKey(query).split(WHITESPACE).filter { it.isNotEmpty() }
    return words.all { it in text }
}

/**
 * Generic foods a product probably belongs to, best first, by shared words between the product's
 * name and categories and each generic's name and aliases. Only a suggestion: the user picks.
 */
fun Library.suggestFamilies(productText: String, limit: Int = 3): List<Food> {
    val words = searchKey(productText).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
    if (words.isEmpty()) return emptyList()
    return foods.values
        .filter { it.kind == app.shoku.data.db.FoodKind.GENERIC && !it.archived && !it.deleted }
        .map { g -> g to searchKey("${g.name} ${g.aliases}").split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.distinct().count { w -> words.any { sameWord(it, w) } } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}

/**
 * Whether a product word means a generic's word: equal, singular/plural ("breads" = "bread"), or a
 * German compound ending in it ("vollkorntoast" ends in "toast"). Not a mere substring: "wholemeal"
 * is not "whole".
 */
private fun sameWord(product: String, generic: String): Boolean {
    fun stem(w: String) = if (w.length > 4 && w.endsWith("es")) w.dropLast(2) else if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w
    val p = stem(product)
    val g = stem(generic)
    return p == g || (p.length > g.length + 2 && p.endsWith(g))
}
