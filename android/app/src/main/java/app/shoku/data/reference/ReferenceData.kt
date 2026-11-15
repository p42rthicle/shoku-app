package app.shoku.data.reference

import android.content.res.AssetManager
import app.shoku.data.backup.Csv
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.domain.Nutrients
import app.shoku.domain.searchKey
import java.util.UUID

/**
 * A food from a bundled reference table. Reference foods are read-only and are not in the database
 * or the sync; one is copied into the library (as a generic food) the first time it's logged.
 */
data class ReferenceFood(
    val source: String,
    val code: String,
    val name: String,
    /** Per 100 g. */
    val per100: Nutrients,
    val servingLabel: String?,
    val servingGrams: Double?,
    val aliases: String,
) {
    val searchText: String = searchKey("$name $aliases")
    val nameKey: String = searchKey(name)
    val aliasKey: String = searchKey(aliases)

    /** Id the food gets when copied into the library; stable, so copying twice gives the same row. */
    val libraryId: String get() = UUID.nameUUIDFromBytes("ref|$source|$code".toByteArray()).toString()

    val units: List<String> get() = listOfNotNull(servingLabel) + "g"

    fun nutrients(unit: String, qty: Double): Nutrients? = when {
        unit == "g" -> per100 * (qty / 100.0)
        unit == servingLabel && servingGrams != null -> per100 * (qty * servingGrams / 100.0)
        else -> null
    }

    fun toFood(now: Long = System.currentTimeMillis()) = Food(
        id = libraryId, name = name, unit = "g", baseQty = 100.0,
        kcal = per100.kcal, protein = per100.protein, fibre = per100.fibre, carbs = per100.carbs, fat = per100.fat,
        source = source, kind = FoodKind.GENERIC, aliases = aliases, updatedAt = now,
    )

    fun toPortion(now: Long = System.currentTimeMillis()): FoodPortion? =
        if (servingLabel != null && servingGrams != null) {
            FoodPortion(UUID.nameUUIDFromBytes("portion|$libraryId|$servingLabel".toByteArray()).toString(), libraryId, servingLabel, servingGrams, updatedAt = now)
        } else null
}

/**
 * Bundled reference tables, loaded once on first use.
 *
 * - INDB: Indian Nutrient Databank (Vijayakumar, Jaacks et al., 2024), recipes commonly eaten in India,
 *   built on ICMR-NIN IFCT 2017. INDB counts all deep-frying oil as eaten; for its 125 fried recipes the
 *   frying oil is replaced by typical absorbed oil (8-30% of cooked weight by food type; see
 *   fried_oil.py in the project notes), so a poori is ~335 kcal/100 g, not 738.
 * - BLS: Bundeslebensmittelschlüssel 4.0 (Max Rubner-Institut, 2025), about 7,100 foods eaten in
 *   Germany; English names with the German name as an alias. Free for use in apps.
 */
class ReferenceData(private val assets: AssetManager) {

    val foods: List<ReferenceFood> by lazy { load("reference_indb.csv", "indb") + load("reference_bls.csv", "bls") }

    private fun load(file: String, source: String): List<ReferenceFood> = runCatching {
        val text = assets.open(file).use { it.readBytes().toString(Charsets.UTF_8) }
        Csv.parse(text).mapNotNull { r ->
            val kcal = r["kcal"]?.toDoubleOrNull() ?: return@mapNotNull null
            ReferenceFood(
                source = source,
                code = r["code"] ?: return@mapNotNull null,
                name = r["name"] ?: return@mapNotNull null,
                per100 = Nutrients(kcal, r["protein"]?.toDoubleOrNull(), r["fibre"]?.toDoubleOrNull(), r["carbs"]?.toDoubleOrNull(), r["fat"]?.toDoubleOrNull()),
                servingLabel = r["serving_label"]?.takeIf { it.isNotBlank() },
                servingGrams = r["serving_g"]?.toDoubleOrNull(),
                aliases = r["aliases"].orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** Best matches first; tolerates plurals and small typos (see matchScore). */
    fun search(query: String, limit: Int = 15): List<ReferenceFood> {
        val q = searchKey(query).split(Regex("\\s+")).filter { it.length >= 2 }.joinToString(" ")
        if (q.isEmpty()) return emptyList()
        return foods.asSequence()
            .map { it to app.shoku.domain.matchScore(q, it.nameKey, it.aliasKey) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ReferenceFood, Int>> { it.second }.thenBy { it.first.name.length })
            .take(limit)
            .map { it.first }
            .toList()
    }
}
