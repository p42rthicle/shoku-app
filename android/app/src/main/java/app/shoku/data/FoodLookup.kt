package app.shoku.data

import app.shoku.data.ai.AiClient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.domain.Library
import app.shoku.domain.searchKey
import app.shoku.domain.suggestFamilies
import kotlinx.coroutines.flow.first

/** A food to confirm before it goes into the library: from a barcode, a label photo, or typed by hand. */
data class FoodDraft(
    val prefill: Food,
    val families: List<Food>,
    /** Values came from somewhere (Open Food Facts or a label), not empty fields. */
    val found: Boolean,
    val fromLabel: Boolean = false,
    val note: String? = null,
    /** Household units to start with, e.g. "slice" = the pack's serving size. */
    val portions: List<FoodPortion> = emptyList(),
)

sealed interface BarcodeResult {
    /** Already in the library. */
    data class Known(val food: Food) : BarcodeResult
    /** Not in the library: confirm Open Food Facts' values, or type them / read the label. */
    data class New(val draft: FoodDraft) : BarcodeResult
    data object Offline : BarcodeResult
}

/** The ways a new food gets into the library, shared by Add and every picker. */
object FoodLookup {
    /** Library by barcode first, then Open Food Facts. */
    suspend fun barcode(repo: ShokuRepository, code: String): BarcodeResult {
        repo.foodByBarcode(code)?.let { return BarcodeResult.Known(it) }
        val library = repo.library.first()
        // The bundled copy first (offline, no rate limit); then Open Food Facts online.
        val product = app.shoku.data.reference.OfflineProducts.lookup(code)
            ?: runCatching { OpenFoodFacts.lookup(code) }.getOrElse { return BarcodeResult.Offline }
        val prefill = Food(
            id = ShokuRepository.newId(),
            // Generic-style name with the brand as a chip ("Vollkorntoast" [Backländer]), like the rest of the library.
            name = product?.cleanName().orEmpty(),
            unit = product?.unit ?: "g", baseQty = 100.0,
            kcal = product?.kcal?.let { Math.round(it * 10) / 10.0 }, protein = product?.protein, fibre = product?.fibre,
            carbs = product?.carbs, fat = product?.fat,
            barcode = code, brand = product?.brand, source = if (product != null) "openfoodfacts" else "manual",
        )
        // The name and the two most specific categories (Open Food Facts lists them general -> specific;
        // broad ones like "cereals and potatoes" would suggest crisps for bread).
        val families = library.suggestFamilies("${product?.name.orEmpty()} ${product?.categories.orEmpty().takeLast(2).joinToString(" ").replace('-', ' ')}")
        // Open Food Facts sometimes knows the pack but not its nutrition (Maggi India, some Haldiram's):
        // that counts as not found, so the label photo comes next instead of a food with empty values.
        val hasNutrition = product?.kcal != null
        val pack = product?.packQty?.takeIf { it <= 400 && it != product.servingQty }
            ?.let { listOf(FoodPortion(java.util.UUID.nameUUIDFromBytes("portion|${prefill.id}|pack".toByteArray()).toString(), prefill.id, "pack", it)) }.orEmpty()
        return BarcodeResult.New(
            FoodDraft(
                prefill, families, found = hasNutrition,
                note = if (product != null && !hasNutrition) "Open Food Facts knows this pack (${listOfNotNull(product.brand, product.cleanName().ifBlank { null }).joinToString(" ")}) but has no nutrition values. Read the label with a photo, or type them." else null,
                portions = servingPortion(prefill, product?.servingQty) + pack,
            ),
        )
    }

    /** A photo of a nutrition table -> a draft (keeps a scanned [barcode]); null when no table could be read. */
    suspend fun label(ai: AiClient, library: Library, jpegBase64: String, barcode: String?): FoodDraft? {
        val r = ai.label(jpegBase64)
        if (!r.readable || r.kcal == null) return null
        val prefill = Food(
            id = ShokuRepository.newId(),
            name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.brand.orEmpty(),
            unit = r.unit, baseQty = 100.0,
            kcal = r.kcal, protein = r.protein, fibre = r.fibre, carbs = r.carbs, fat = r.fat,
            barcode = barcode, brand = r.brand, source = "label",
        )
        return FoodDraft(prefill, library.suggestFamilies("${r.name.orEmpty()} ${r.brand.orEmpty()}"), found = true, fromLabel = true, note = r.note,
            portions = servingPortion(prefill, r.servingSize))
    }

    /** An empty food named [name], to type the values in (or read them from the label). */
    fun manual(library: Library, name: String): FoodDraft =
        FoodDraft(
            Food(id = ShokuRepository.newId(), name = name.trim(), unit = "g", baseQty = 100.0, kcal = null, protein = null, fibre = null, source = "manual"),
            if (name.isBlank()) emptyList() else library.suggestFamilies(name), found = false,
        )

    /** Saves a confirmed draft with its units and returns once the library has it. */
    suspend fun save(repo: ShokuRepository, food: Food, portions: List<FoodPortion>): Library {
        repo.saveFood(food, updatePast = false)
        if (portions.isNotEmpty()) repo.savePortions(portions)
        return repo.library.first { it.foods.containsKey(food.id) }
    }

    /**
     * The pack's serving as a unit, named after what it usually is: a slice of bread, a piece of biscuit,
     * a bar, otherwise "serving". Editable before saving.
     */
    fun servingPortion(food: Food, qty: Double?): List<FoodPortion> {
        if (qty == null || qty <= 0 || food.unit !in app.shoku.domain.WEIGHT_UNITS) return emptyList()
        val n = searchKey("${food.name} ${food.brand.orEmpty()}")
        val label = when {
            listOf("bread", "brot", "toast", "scheibe", "slice", "loaf", "cheese slice", "kase").any { it in n } -> "slice"
            listOf("biscuit", "cookie", "keks", "rusk", "cracker", "wafer", "waffel").any { it in n } -> "piece"
            listOf("bar", "riegel").any { Regex("\\b$it\\b").containsMatchIn(n) } -> "bar"
            else -> "serving"
        }
        return listOf(FoodPortion(java.util.UUID.randomUUID().toString(), food.id, label, qty))
    }
}
