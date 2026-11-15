package app.shoku.data.reference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** What Open Food Facts knows about a barcode, per 100 g or 100 ml. */
data class ScannedProduct(
    val barcode: String,
    val name: String?,
    val brand: String?,
    val unit: String,
    val kcal: Double?,
    val protein: Double?,
    val fibre: Double?,
    val carbs: Double?,
    val fat: Double?,
    val categories: List<String>,
    /** Grams (or ml) in one serving and in the whole pack, when Open Food Facts knows them. */
    val servingQty: Double? = null,
    val packQty: Double? = null,
) {
    /** The product name without the brand in front ("Milka Oreo" by Oreo stays, "Nutella" by Ferrero stays). */
    fun cleanName(): String {
        val n = name?.trim().orEmpty()
        val b = brand?.trim().orEmpty()
        val stripped = if (b.isNotEmpty() && n.length > b.length + 2 && n.lowercase().startsWith(b.lowercase() + " ")) n.substring(b.length).trim() else n
        return stripped.ifBlank { b.ifBlank { "Product $barcode" } }
    }
}

/**
 * Barcode lookup against Open Food Facts (open data, ODbL). One request per scan; the result is
 * saved as a food in the library, so the same barcode never needs the network again.
 */
object OpenFoodFacts {
    // Open Food Facts asks every app to identify itself. No personal details are sent.
    private const val USER_AGENT = "Shoku/0.7 (personal food logger; Android)"
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the product isn't in the database. Throws on network errors. */
    suspend fun lookup(barcode: String): ScannedProduct? = withContext(Dispatchers.IO) {
        app.shoku.data.net.Network.ensureAllowed("Open Food Facts")
        val fields = "product_name,product_name_de,product_name_en,brands,nutriments,quantity,product_quantity,serving_quantity,categories_tags"
        val conn = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$fields").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) throw IllegalStateException("Open Food Facts said ${conn.responseCode}")
            val root = json.parseToJsonElement(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
            if (root["status"]?.jsonPrimitive?.contentOrNull == "0") return@withContext null
            val p = root["product"]?.jsonObject ?: return@withContext null
            parse(barcode, p)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Product search. Open Food Facts' own ranking is loose (any word can match), so this fetches the 100
     * most-scanned hits and keeps only products where every typed word is in the name, brand or
     * category ("crownfield musli" -> Crownfield müslis, not Crownfield oat flakes), best match first.
     * When that finds little, a second, relevance-ordered page is added. Throws on network errors.
     */
    suspend fun search(text: String, limit: Int = 10): List<ScannedProduct> = withContext(Dispatchers.IO) {
        val query = key(text).split(' ').filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return@withContext emptyList()
        // Tests (see Network): nothing online, quietly, so screens look as they do when nothing matches.
        val builtIn = OfflineProducts.search(query, limit)
        if (app.shoku.data.net.Network.blocked) return@withContext builtIn
        val popular = runCatching { fetch(text, popular = true) }.getOrElse { if (builtIn.isNotEmpty()) return@withContext builtIn else throw it }
        val ranked = rank(query, popular)
        val all = if (ranked.size >= 3) ranked else (ranked + rank(query, fetch(text, popular = false))).distinctBy { it.first.barcode }
        (builtIn + all.map { it.first }.filter { p -> builtIn.none { it.barcode == p.barcode } })
            .filter { it.kcal != null && !it.name.isNullOrBlank() }
            .distinctBy { "${it.name?.lowercase()}|${it.brand?.lowercase()}|${it.kcal?.let(Math::round)}" }
            .take(limit)
    }

    /** Lowercase, umlauts folded, apostrophes dropped ("Lay's" -> "lays"), "muesli" spelled like "müsli". */
    private fun key(s: String) = app.shoku.domain.searchKey(s).replace("'", "").replace("’", "").replace("muesli", "musli")

    /** Products that contain every query word, best match first (stable, so popularity breaks ties). */
    private fun rank(query: String, hits: List<Pair<ScannedProduct, String>>): List<Pair<ScannedProduct, Int>> =
        hits.mapNotNull { (p, extra) ->
            val title = key("${p.name.orEmpty()} ${p.brand.orEmpty()}")
            // Several words ("milk cake"): every word in the name or brand. Categories ("biscuits-and-cakes")
            // and other names only help single words.
            if (query.contains(' ') && app.shoku.domain.matchScore(query, title, "") <= 0) return@mapNotNull null
            val score = app.shoku.domain.matchScore(query, title, key(extra))
            if (score > 0) p to score else null
        }.sortedByDescending { it.second }

    /** One page of hits, each with the extra text it can be found by (other names, all brands, categories). */
    private fun fetch(text: String, popular: Boolean): List<Pair<ScannedProduct, String>> {
        app.shoku.data.net.Network.ensureAllowed("Open Food Facts")
        val fields = "code,product_name,product_name_de,product_name_en,generic_name,brands,nutriments,quantity,product_quantity,serving_quantity,categories_tags"
        val url = "https://search.openfoodfacts.org/search?q=" + java.net.URLEncoder.encode(text, "UTF-8") +
            (if (popular) "&sort_by=-unique_scans_n&page_size=100" else "&page_size=50") + "&fields=$fields"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) throw IllegalStateException("Open Food Facts said ${conn.responseCode}")
            val root = json.parseToJsonElement(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
            return root["hits"]?.jsonArray.orEmpty().mapNotNull { h ->
                val o = h.jsonObject
                val code = o["code"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // search.openfoodfacts.org returns brands as a list
                val brands = o["brands"]?.let { b -> runCatching { b.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() } }.getOrNull() ?: listOfNotNull(b.jsonPrimitive.contentOrNull) }.orEmpty()
                val fixed = JsonObject(o + ("brands" to kotlinx.serialization.json.JsonPrimitive(brands.joinToString(","))))
                fun str(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
                val categories = o["categories_tags"]?.let { runCatching { it.jsonArray.mapNotNull { c -> c.jsonPrimitive.contentOrNull?.substringAfter(':')?.replace('-', ' ') } }.getOrNull() }.orEmpty()
                parse(code, fixed) to listOf(str("product_name_de"), str("product_name_en"), str("generic_name"), brands.joinToString(" "), categories.joinToString(" ")).joinToString(" ")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(barcode: String, p: JsonObject): ScannedProduct {
        fun str(key: String) = p[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val n = p["nutriments"]?.jsonObject
        fun num(key: String) = n?.get(key)?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val categories = p["categories_tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.substringAfter(':') }.orEmpty()
        val quantity = str("quantity").orEmpty().lowercase()
        // A drink when the pack size is a volume or it's filed under drinks. Not "plant-based-foods-and-beverages",
        // which nearly every plant food (müsli, crisps) is filed under.
        val drinkCategories = setOf("beverages", "drinks", "milks", "dairy-drinks", "juices", "sodas", "waters", "beers", "plant-milks")
        val liquid = Regex("""\d\s*(ml|cl|l)\b""").containsMatchIn(quantity) || categories.any { it in drinkCategories }
        val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 } // energy_100g is in kJ
        return ScannedProduct(
            barcode = barcode,
            name = str("product_name") ?: str("product_name_de") ?: str("product_name_en"),
            brand = str("brands")?.split(',')?.firstOrNull()?.trim(),
            unit = if (liquid) "ml" else "g",
            kcal = kcal, protein = num("proteins_100g"), fibre = num("fiber_100g"),
            carbs = num("carbohydrates_100g"), fat = num("fat_100g"),
            categories = categories,
            servingQty = p["serving_quantity"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it > 0 },
            // product_quantity is the pack in g or ml; else read "400 g", "400 g ℮", "1,5 l" from the label text.
            packQty = p["product_quantity"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it > 0 }
                ?: Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(g|ml|l|kg)\b""").find(quantity)?.let { m ->
                    val n = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                    if (m.groupValues[2] == "l" || m.groupValues[2] == "kg") n?.times(1000) else n
                },
        )
    }
}

/** The library food (and serving/pack portions) an online product becomes. Ids are stable per barcode. */
fun ScannedProduct.toLibraryFood(): Pair<app.shoku.data.db.Food, List<app.shoku.data.db.FoodPortion>> {
    val id = java.util.UUID.nameUUIDFromBytes("off|$barcode".toByteArray()).toString()
    val food = app.shoku.data.db.Food(
        id = id, name = cleanName(), unit = unit, baseQty = 100.0,
        kcal = kcal?.let { Math.round(it * 10) / 10.0 }, protein = protein, fibre = fibre, carbs = carbs, fat = fat,
        barcode = barcode, brand = brand, source = "openfoodfacts",
    )
    // Serving and pack sizes are offered as suggestions in the food editor, never added on their own.
    return food to emptyList()
}
