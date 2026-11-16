package app.shoku.domain

/**
 * What someone typed into search, split into the food words and an amount:
 * "nutella 4 tbsp" -> text "nutella", 4, "tbsp"; "200g skyr" -> "skyr", 200, "g"; "2 eggs" -> "eggs", 2, null.
 * [multiple] is true for "2 toast and an egg"-style text, which is better handed to the AI.
 */
data class ParsedQuery(val text: String, val qty: Double?, val unit: String?, val multiple: Boolean) {
    val hasAmount: Boolean get() = qty != null
}

/** Typed unit words to the unit labels used in the app. Weights convert to g / ml. */
private val UNIT_WORDS: Map<String, Pair<String, Double>> = buildMap {
    fun put(label: String, factor: Double = 1.0, vararg words: String) = words.forEach { put(it, label to factor) }
    put("g", 1.0, "g", "gm", "gms", "gr", "gram", "grams", "gramm")
    put("g", 1000.0, "kg", "kilo", "kilos")
    put("ml", 1.0, "ml", "milliliter", "millilitre", "milliliters", "millilitres")
    put("ml", 10.0, "cl")
    put("ml", 1000.0, "l", "litre", "liter", "litres", "liters", "ltr")
    put("tbsp", 1.0, "tbsp", "tbs", "tablespoon", "tablespoons", "el", "essloffel")
    put("tsp", 1.0, "tsp", "teaspoon", "teaspoons", "tl", "teeloffel")
    put("cup", 1.0, "cup", "cups", "tasse")
    put("glass", 1.0, "glass", "glasses", "glas")
    put("slice", 1.0, "slice", "slices", "scheibe", "scheiben")
    put("pc", 1.0, "pc", "pcs", "piece", "pieces", "stuck", "x")
    put("katori", 1.0, "katori", "katoris", "bowl", "bowls")
    put("scoop", 1.0, "scoop", "scoops")
    put("serving", 1.0, "serving", "servings", "portion", "portions", "plate", "plates")
    put("small", 1.0, "small")
    put("medium", 1.0, "medium")
    put("large", 1.0, "large", "big")
    put("can", 1.0, "can", "cans", "tin", "tins", "dose")
    put("bar", 1.0, "bar", "bars", "riegel")
    put("pack", 1.0, "pack", "packs", "packet", "packets", "sachet", "sachets")
}

/** Labels that mean the same portion, so "katori" finds a food whose portion is called "bowl". */
val UNIT_SYNONYMS: Map<String, Set<String>> = mapOf(
    "pc" to setOf("pc", "piece", "pcs"),
    "katori" to setOf("katori", "bowl"),
    "can" to setOf("can", "tin"),
    "pack" to setOf("pack", "packet", "sachet"),
    "serving" to setOf("serving", "serve", "portion"),
)

private val NUMBER_WORDS = mapOf(
    "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
    "half" to 0.5, "quarter" to 0.25, "ein" to 1.0, "eine" to 1.0, "zwei" to 2.0, "drei" to 3.0, "halb" to 0.5,
)

private fun number(token: String): Double? {
    val t = token.replace(',', '.')
    NUMBER_WORDS[t]?.let { return it }
    when (t) { "½" -> return 0.5; "¼" -> return 0.25; "¾" -> return 0.75; "1½" -> return 1.5 }
    Regex("""(\d+)/(\d+)""").matchEntire(t)?.let { m -> return m.groupValues[1].toDouble() / m.groupValues[2].toDouble().coerceAtLeast(1.0) }
    return t.toDoubleOrNull()?.takeIf { it > 0 && it < 100_000 }
}

fun parseQuery(raw: String): ParsedQuery {
    val key = searchKey(raw).trim()
    val multiple = Regex("""\b(and|und|with|mit|plus)\b|,|\+|&""").containsMatchIn(key)
    // "200g" / "4tbsp" / "2x" -> "200 g"
    val spaced = key.replace(Regex("""(\d)([a-z½¼¾])"""), "$1 $2").replace(Regex("""([½¼¾])(?=[a-z])"""), "$1 ")
    val tokens = spaced.split(SPACES).filter { it.isNotEmpty() }.toMutableList()
    if (multiple || tokens.size < 2) return ParsedQuery(key, null, null, multiple)

    var qty: Double? = null
    var unit: String? = null
    var factor = 1.0
    val used = BooleanArray(tokens.size)
    for (i in tokens.indices) {
        // Number words only count when a unit or food word follows ("a tbsp", not "a" alone).
        val n = number(tokens[i]) ?: continue
        if (tokens[i] in NUMBER_WORDS && i == tokens.lastIndex) continue
        qty = n
        used[i] = true
        tokens.getOrNull(i + 1)?.let { next ->
            UNIT_WORDS[next]?.let { (label, f) -> unit = label; factor = f; used[i + 1] = true }
        }
        // "of" after a unit: "2 slices of toast"
        if (unit != null && tokens.getOrNull(i + 2) in setOf("of", "von")) used[i + 2] = true
        break
    }
    if (qty == null) {
        // A unit word without a number: "tbsp nutella" means 1 tbsp. Size words alone are food words.
        tokens.forEachIndexed { i, t ->
            if (unit == null && t !in setOf("small", "medium", "large", "big", "x", "l", "el", "tl", "can", "bar", "dose")) {
                UNIT_WORDS[t]?.let { (label, f) -> unit = label; factor = f; qty = 1.0; used[i] = true }
            }
        }
    }
    val text = tokens.filterIndexed { i, _ -> !used[i] }.joinToString(" ")
    if (text.isBlank()) return ParsedQuery(key, null, null, false)
    return ParsedQuery(text, qty?.times(factor), unit, false)
}

/**
 * The unit and amount to prefill for an item with [units], given what was typed. Null when the typed
 * amount doesn't fit any of the item's units (then the item's usual amount is used).
 */
fun amountFor(units: List<String>, parsed: ParsedQuery): Pair<String, Double>? {
    val qty = parsed.qty ?: return null
    val want = parsed.unit
    if (want == null) {
        // "skyr 200" means grams; "2 eggs" means two of the first countable unit ("medium", "slice", "pc").
        if (qty >= 20) return units.firstOrNull { it in WEIGHT_UNITS }?.let { it to qty }
        val countable = units.firstOrNull { it !in WEIGHT_UNITS } ?: return null
        return countable to qty
    }
    val names = UNIT_SYNONYMS[want] ?: setOf(want)
    units.firstOrNull { it in names }?.let { return it to qty }
    return null
}

/** Edit distance of at most [max] between two short words, without building the full table. */
fun withinEdits(a: String, b: String, max: Int): Boolean {
    if (kotlin.math.abs(a.length - b.length) > max) return false
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            rowMin = minOf(rowMin, cur[j])
        }
        if (rowMin > max) return false
        prev = cur
    }
    return prev[b.length] <= max
}

/**
 * How well [query] (a searchKey) matches an item: higher is better, 0 means no match.
 * [name] is the item's searchKey'd name; [extra] is brand, aliases and family names.
 * Every query word must appear (or be one typo away, for words of 4+ letters).
 */
fun matchScore(query: String, name: String, extra: String, family: String = ""): Int {
    val own = matchScoreOwn(query, name, extra)
    if (own > 0 || family.isEmpty()) return own
    // Found only through its generic ("nutella" -> Chocolate spread -> a MisterChoc product): rank low.
    return matchScoreOwn(query, name, "$extra $family") / 4
}

/** Compiled once: building a Regex per call made scoring thousands of rows freeze the app. */
private val NON_WORD = Regex("[^a-z0-9]+")
private val SPACES = Regex("\\s+")

private fun matchScoreOwn(query: String, name: String, extra: String): Int {
    val words = query.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return 0
    val all = "$name $extra"
    val nameWords = name.split(NON_WORD).filter { it.isNotEmpty() }
    val allWords = all.split(NON_WORD).filter { it.isNotEmpty() }
    var score = 0
    for (w in words) {
        score += when {
            // Singular and plural count as the same word ("seeds" = "seed", "tomatoes" = "tomato").
            nameWords.any { it == w || stem(it) == stem(w) } -> 30
            nameWords.any { it.startsWith(w) } -> 22
            w in name -> 14
            allWords.any { it.startsWith(w) } -> 12
            w in all -> 8
            // Singular/plural and typos: "eggs" -> "egg", "oreao" -> "oreo"
            w.length >= 4 && allWords.any { it.length >= 3 && (w.startsWith(it) && w.length - it.length <= 2) } -> 10
            w.length >= 4 && allWords.any { it.length >= 4 && withinEdits(it, w, if (w.length >= 9) 2 else 1) } -> 5
            else -> return 0
        }
    }
    val stemmedName = nameWords.joinToString(" ") { stem(it) }
    val stemmedQuery = words.joinToString(" ") { stem(it) }
    if (name == query || stemmedName == stemmedQuery) score += 40
    else if (name.startsWith(query) || stemmedName.startsWith(stemmedQuery)) score += 20
    return score
}

/** Crude English singular: "seeds" -> "seed", "tomatoes" -> "tomato", "berries" -> "berry". */
private fun stem(w: String): String = when {
    w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
    w.length > 4 && w.endsWith("oes") -> w.dropLast(2)
    w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
    else -> w
}
