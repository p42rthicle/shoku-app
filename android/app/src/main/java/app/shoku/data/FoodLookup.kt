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
    /** From a photo of the pack (no barcode match, maybe no label): typical values when [fromLabel] is false. */
    val fromPack: Boolean = false,
    val note: String? = null,
    /** The whole pack (1000 g of rice), when the barcode database knows it: where "add to stock" starts. */
    val packQty: Double? = null,
    /** Household units to start with, e.g. "slice" = the pack's serving size. */
    val portions: List<FoodPortion> = emptyList(),
    /** Barcode and a later label/pack photo disagree here: what each said, and which one is used. */
    val choices: List<FieldChoice> = emptyList(),
    /** What the barcode and the photo each gave, so a choice can be switched. */
    val merged: Pair<Food, Food>? = null,
    /** "label" or "pack": what the second source was. */
    val photoKind: String? = null,
)

/** One field the barcode and the photo disagree on. [fromPhoto]: the photo's value is the one used. */
data class FieldChoice(val field: String, val label: String, val barcode: String, val photo: String, val fromPhoto: Boolean)

/**
 * A barcode's draft (Open Food Facts) and a label or pack photo read afterwards, combined: the barcode
 * stays first for name and brand, the photo fills in whatever the barcode lacked (calories, protein…).
 * Where both have a value and they differ, both are kept as a [FieldChoice]: name and brand from the
 * barcode, the numbers from the photo (read off the actual pack), each switchable in the editor.
 */
object DraftMerge {
    private val LABELS = linkedMapOf("name" to "Name", "brand" to "Brand", "unit" to "Per 100", "kcal" to "Calories", "protein" to "Protein", "fibre" to "Fibre", "carbs" to "Carbs", "fat" to "Fat")

    /** Only a draft with something from the barcode is worth keeping when a photo comes in. */
    fun worthKeeping(d: FoodDraft) = d.prefill.barcode != null || d.found

    fun merge(barcode: FoodDraft, photo: FoodDraft): FoodDraft {
        val a = barcode.prefill
        val b = photo.prefill
        val choices = LABELS.keys.mapNotNull { f ->
            val x = text(a, f) ?: return@mapNotNull null
            val y = text(b, f) ?: return@mapNotNull null
            if (same(f, a, b)) null else FieldChoice(f, LABELS.getValue(f), x, y, fromPhoto = f !in setOf("name", "brand"))
        }
        val draft = barcode.copy(
            prefill = a,
            families = (barcode.families + photo.families).distinctBy { it.id },
            found = true,
            fromLabel = photo.fromLabel,
            note = if (choices.isEmpty()) "Name from the barcode, the missing values from the ${if (photo.fromPack) "pack photo" else "label"}." else null,
            portions = (barcode.portions + photo.portions).distinctBy { it.label },
            packQty = barcode.packQty ?: photo.packQty,
            choices = choices,
            merged = a to b,
            photoKind = if (photo.fromPack && !photo.fromLabel) "pack" else "label",
        )
        return apply(draft)
    }

    /** Switches one field between the barcode's and the photo's value. */
    fun choose(d: FoodDraft, field: String, fromPhoto: Boolean): FoodDraft =
        apply(d.copy(choices = d.choices.map { if (it.field == field) it.copy(fromPhoto = fromPhoto) else it }))

    /** The food as chosen: the barcode's values, gaps filled from the photo, then each choice. */
    private fun apply(d: FoodDraft): FoodDraft {
        val (a, b) = d.merged ?: return d
        val pick = d.choices.associate { it.field to it.fromPhoto }
        fun <T> v(f: String, x: T?, y: T?): T? = when (pick[f]) { true -> y ?: x; false -> x ?: y; null -> x ?: y }
        val photoUnit = pick["unit"] == true
        return d.copy(prefill = a.copy(
            name = v("name", a.name.takeIf { it.isNotBlank() }, b.name.takeIf { it.isNotBlank() }) ?: a.name,
            brand = v("brand", a.brand?.takeIf { it.isNotBlank() }, b.brand?.takeIf { it.isNotBlank() }),
            unit = if (photoUnit) b.unit else a.unit,
            baseQty = if (photoUnit) b.baseQty else a.baseQty,
            kcal = v("kcal", a.kcal, b.kcal), protein = v("protein", a.protein, b.protein), fibre = v("fibre", a.fibre, b.fibre),
            carbs = v("carbs", a.carbs, b.carbs), fat = v("fat", a.fat, b.fat),
            barcode = a.barcode ?: b.barcode,
        ))
    }

    private fun num(f: Food, field: String): Double? = when (field) {
        "kcal" -> f.kcal; "protein" -> f.protein; "fibre" -> f.fibre; "carbs" -> f.carbs; "fat" -> f.fat; else -> null
    }

    private fun text(f: Food, field: String): String? = when (field) {
        "name" -> f.name.takeIf { it.isNotBlank() }
        "brand" -> f.brand?.takeIf { it.isNotBlank() }
        "unit" -> "${app.shoku.ui.formatQty(f.baseQty)} ${f.unit}"
        else -> num(f, field)?.let { app.shoku.ui.formatQty(it) + if (field == "kcal") " kcal" else " g" }
    }

    private fun same(field: String, a: Food, b: Food): Boolean = when (field) {
        "name" -> app.shoku.domain.searchKey(a.name) == app.shoku.domain.searchKey(b.name)
        "brand" -> app.shoku.domain.searchKey(a.brand.orEmpty()) == app.shoku.domain.searchKey(b.brand.orEmpty())
        "unit" -> a.unit == b.unit && a.baseQty == b.baseQty
        else -> {
            val x = num(a, field)!!
            val y = num(b, field)!!
            Math.abs(x - y) <= maxOf(0.5, 0.02 * maxOf(Math.abs(x), Math.abs(y)))
        }
    }
}

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
        product?.packQty?.takeIf { it > 0 }?.let { PackSizes.remember(prefill.id, it) }
        val pack = product?.packQty?.takeIf { it <= 400 && it != product.servingQty }
            ?.let { listOf(FoodPortion(java.util.UUID.nameUUIDFromBytes("portion|${prefill.id}|pack".toByteArray()).toString(), prefill.id, "pack", it)) }.orEmpty()
        return BarcodeResult.New(
            FoodDraft(
                prefill, families, found = hasNutrition,
                note = if (product != null && !hasNutrition) "Open Food Facts knows this pack (${listOfNotNull(product.brand, product.cleanName().ifBlank { null }).joinToString(" ")}) but has no nutrition values. Read the label with a photo, or type them." else null,
                portions = servingPortion(prefill, product?.servingQty) + pack,
                packQty = product?.packQty?.takeIf { it > 0 },
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

    /**
     * Photos of a pack (front, back) -> a draft: brand and name from the front, values from its nutrition table
     * if one shows, else typical ones for that kind of product. Null when the product can't be told.
     */
    suspend fun product(ai: AiClient, library: Library, jpegsBase64: List<String>, barcode: String?): FoodDraft? {
        val r = ai.product(jpegsBase64)
        val name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.brand?.trim()?.takeIf { it.isNotEmpty() }
        if (!r.identified || name == null) return null
        val prefill = Food(
            id = ShokuRepository.newId(),
            name = name,
            unit = r.unit, baseQty = 100.0,
            kcal = r.kcal, protein = r.protein, fibre = r.fibre, carbs = r.carbs, fat = r.fat,
            barcode = barcode, brand = r.brand?.trim()?.takeIf { it.isNotEmpty() }, source = if (r.fromLabel) "label" else "pack",
        )
        return FoodDraft(prefill, library.suggestFamilies("$name ${r.brand.orEmpty()}"), found = true, fromLabel = r.fromLabel, fromPack = true, note = r.note,
            portions = servingPortion(prefill, r.servingSize), packQty = r.packSize?.takeIf { it > 0 })
    }

    /** The label photo, and when it shows no nutrition table, what the product is from the same photo. */
    suspend fun labelOrProduct(ai: AiClient, library: Library, jpegBase64: String, barcode: String?): FoodDraft? =
        label(ai, library, jpegBase64, barcode) ?: product(ai, library, listOf(jpegBase64), barcode)

    /** An empty food named [name], to type the values in (or read them from the label). */
    fun manual(library: Library, name: String): FoodDraft =
        FoodDraft(
            Food(id = ShokuRepository.newId(), name = name.trim(), unit = "g", baseQty = 100.0, kcal = null, protein = null, fibre = null, source = "manual"),
            if (name.isBlank()) emptyList() else library.suggestFamilies(name), found = false,
        )

    /**
     * "Already in your library": use [existing] instead of a new food. A scanned barcode (and a brand or
     * missing values) are added to it, so the next scan finds it straight away.
     */
    suspend fun useExisting(repo: ShokuRepository, existing: Food, draft: FoodDraft): Food {
        val p = draft.prefill
        val sameBasis = p.unit == existing.unit && p.baseQty == existing.baseQty
        val updated = existing.copy(
            barcode = existing.barcode?.takeIf { it.isNotBlank() } ?: p.barcode,
            brand = existing.brand?.takeIf { it.isNotBlank() } ?: p.brand,
            kcal = existing.kcal ?: p.kcal?.takeIf { sameBasis }, protein = existing.protein ?: p.protein?.takeIf { sameBasis },
            fibre = existing.fibre ?: p.fibre?.takeIf { sameBasis }, carbs = existing.carbs ?: p.carbs?.takeIf { sameBasis }, fat = existing.fat ?: p.fat?.takeIf { sameBasis },
            archived = false,
        )
        if (updated != existing) repo.saveFood(updated, updatePast = false)
        draft.packQty?.let { PackSizes.remember(updated.id, it) }
        return updated
    }

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
            listOf("creatine", "kreatin", "whey", "protein powder", "pulver", "powder", "electrolyte").any { it in n } -> "scoop"
            else -> "serving"
        }
        return listOf(FoodPortion(java.util.UUID.randomUUID().toString(), food.id, label, qty))
    }
}

/**
 * Pack sizes seen while scanning (a 1000 g bag of rice), by food id, so "add to stock" starts at the whole
 * pack. Kept while the app runs; a food bought before starts at the amount added last time instead.
 */
object PackSizes {
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Double>()
    fun remember(foodId: String, qty: Double) { sizes[foodId] = qty }
    fun of(foodId: String): Double? = sizes[foodId]
}
