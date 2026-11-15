package app.shoku.data.reference

import android.content.Context
import app.shoku.data.backup.Csv

/**
 * Packaged products bundled with the app (assets/off_india.csv, built by tools/off_india.py from
 * Open Food Facts' Indian products, ODbL). Checked before going online, so common Indian packs scan
 * offline and without Open Food Facts' rate limits. Loaded on the first scan only; missing file = empty.
 */
object OfflineProducts {
    private var context: Context? = null
    fun init(context: Context) { this.context = context.applicationContext }

    private val byCode: Map<String, ScannedProduct> by lazy {
        val ctx = context ?: return@lazy emptyMap()
        runCatching {
            val text = ctx.assets.open("off_india.csv").use { it.readBytes().toString(Charsets.UTF_8) }
            Csv.parse(text).mapNotNull { r ->
                val code = r["code"]?.trim().orEmpty()
                fun d(k: String) = r[k]?.toDoubleOrNull()
                val kcal = d("kcal") ?: return@mapNotNull null
                code to ScannedProduct(
                    barcode = code, name = r["name"]?.ifBlank { null }, brand = r["brand"]?.ifBlank { null },
                    unit = r["unit"]?.ifBlank { null } ?: "g",
                    kcal = kcal, protein = d("protein"), fibre = d("fibre"), carbs = d("carbs"), fat = d("fat"),
                    categories = emptyList(), servingQty = d("serving"), packQty = d("pack"),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** The bundled product for [barcode] (EAN-13 or UPC, leading zeros tolerated), or null. */
    fun lookup(barcode: String): ScannedProduct? =
        byCode[barcode] ?: byCode[barcode.trimStart('0')] ?: byCode["0$barcode"]

    val size: Int get() = byCode.size

    /** Built-in products whose name and brand contain every word of [query] (already a search key), best first. */
    fun search(query: String, limit: Int = 10): List<ScannedProduct> {
        if (query.isBlank()) return emptyList()
        return byCode.values.mapNotNull { p ->
            val s = app.shoku.domain.matchScore(query, app.shoku.domain.searchKey("${p.name.orEmpty()} ${p.brand.orEmpty()}"), "")
            if (s > 0) p to s else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
