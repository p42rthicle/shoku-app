package app.shoku.data.ai

import app.shoku.data.db.Basis
import app.shoku.data.db.FoodKind
import app.shoku.data.db.RefType
import app.shoku.domain.Library
import app.shoku.domain.Nutrients
import app.shoku.data.reference.ReferenceData
import app.shoku.data.reference.ReferenceFood
import app.shoku.domain.searchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

// ---------- Wire format (matches backend/functions/api/ai.js) ----------

@Serializable
data class LibItem(val k: String, val name: String, val units: List<String>, val aka: String = "")

@Serializable
data class AiEstimate(
    @SerialName("kcal_per_100g") val kcal: Double,
    @SerialName("protein_per_100g") val protein: Double,
    @SerialName("fibre_per_100g") val fibre: Double,
    @SerialName("carbs_per_100g") val carbs: Double,
    @SerialName("fat_per_100g") val fat: Double,
    val grams: Double,
)

@Serializable
data class AiItem(
    val said: String,
    @SerialName("match_key") val matchKey: String? = null,
    val name: String,
    val unit: String,
    val qty: Double,
    val confidence: String,
    val estimate: AiEstimate? = null,
    val brand: String? = null,
)

@Serializable data class AiMealResult(val items: List<AiItem>, val meal: String? = null)
@Serializable data class AiRecipeResult(val name: String? = null, val servings: Double? = null, val ingredients: List<AiItem>)

/** A photo of any side of a pack: what the product is, with values read from it or typical ones. */
@Serializable
data class AiProductResult(
    val identified: Boolean,
    @SerialName("from_label") val fromLabel: Boolean = false,
    val name: String? = null,
    val brand: String? = null,
    val unit: String = "g",
    val kcal: Double? = null,
    val protein: Double? = null,
    val fibre: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    val note: String? = null,
)

@Serializable
data class AiLabelResult(
    val readable: Boolean,
    val name: String? = null,
    val brand: String? = null,
    val unit: String = "g",
    val kcal: Double? = null,
    val protein: Double? = null,
    val fibre: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    val note: String? = null,
)

@Serializable private data class Envelope<T>(val data: T)
@Serializable data class AiUsage(val month: String, val usd: Double, val calls: Int, val capUsd: Double)
@Serializable private data class CapBody(val usd: Double)
@Serializable private data class TextBody(val text: String, val library: List<LibItem>? = null)
@Serializable private data class ImageBody(val image: String, val mediaType: String = "image/jpeg", val text: String? = null, val library: List<LibItem>? = null)
@Serializable private data class ImagesBody(val images: List<String>, val mediaType: String = "image/jpeg", val text: String? = null, val library: List<LibItem>? = null)

/** What an AI item resolves to in this app. */
data class ResolvedItem(
    val name: String,
    val refType: String,
    val refId: String?,
    val unit: String,
    val qty: Double,
    val nutrients: Nutrients?,
    val basis: String,
    /** Per 100 g when the item is an estimate rather than a library match; used when the amount is edited. */
    val per100: Nutrients?,
    val confidence: String,
    val said: String,
    /** Set when matched to a reference database dish; it's copied into the library when logged. */
    val reference: ReferenceFood? = null,
    /** The brand they named or the photo showed ("Yadav"); editable in the review. */
    val brand: String? = null,
) {
    val units: List<String> get() = reference?.units ?: emptyList()
}

/**
 * Calls the AI routes, on the backend or straight to Anthropic (see [AiTransports]). The app sends a
 * compact library with short keys ("f12") so the prompt stays small; [LibraryKeys] maps answers back to real ids.
 */
class AiClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    class LibraryKeys(val items: List<LibItem>, val refs: Map<String, Pair<String, String>>, val references: Map<String, ReferenceFood> = emptyMap())

    /**
     * Reference dishes worth offering for this text: each word (and its singular) is searched in the
     * Indian and German databases. Keeps the prompt small while letting "roti" match real numbers.
     */
    fun referenceCandidates(text: String, reference: ReferenceData, library: Library, limit: Int = 24): List<ReferenceFood> {
        val words = searchKey(text).split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in STOP_WORDS }.distinct()
        val found = LinkedHashMap<String, ReferenceFood>()
        for (w in words) {
            for (q in listOf(w, w.removeSuffix("es"), w.removeSuffix("s")).distinct().filter { it.length >= 3 }) {
                reference.search(q, limit = 4).forEach { r -> if (!library.hasLive(r.libraryId)) found.putIfAbsent("${r.source}:${r.code}", r) }
            }
            if (found.size >= limit) break
        }
        return found.values.take(limit)
    }

    /** Foods (not archived), dishes, batches cooked in the last two weeks, and any reference [extra] dishes. */
    fun libraryKeys(library: Library, today: LocalDate = LocalDate.now(), extra: List<ReferenceFood> = emptyList()): LibraryKeys {
        val items = ArrayList<LibItem>()
        val refs = HashMap<String, Pair<String, String>>()
        val references = HashMap<String, ReferenceFood>()
        extra.forEachIndexed { i, r ->
            val k = "r$i"
            items += LibItem(k, "${r.name} (${if (r.source == "indb") "Indian dish database" else "German food database"})", r.units, r.aliases)
            refs[k] = "reference" to r.code
            references[k] = r
        }
        library.foods.values.filter { !it.archived && !it.deleted }.sortedBy { it.name }.forEachIndexed { i, f ->
            val k = "f$i"
            val label = when {
                f.kind == FoodKind.GENERIC -> "${f.name} (any brand)"
                !f.brand.isNullOrBlank() -> "${f.name} (${f.brand})"
                else -> f.name
            }
            items += LibItem(k, label, library.unitsFor(RefType.FOOD, f.id), f.aliases)
            refs[k] = RefType.FOOD to f.id
        }
        library.dishes.values.filter { !it.archived && !it.deleted }.sortedBy { it.name }.forEachIndexed { i, d ->
            val k = "d$i"
            items += LibItem(k, "${d.name} (own recipe)", library.unitsFor(RefType.DISH, d.id))
            refs[k] = RefType.DISH to d.id
        }
        val from = today.minusDays(14).toString()
        library.batches.values.filter { !it.finished && !it.deleted && it.date >= from }.sortedBy { it.date }.forEachIndexed { i, b ->
            val k = "b$i"
            items += LibItem(k, "${b.dishName} (batch cooked ${b.date})", library.unitsFor(RefType.BATCH, b.id))
            refs[k] = RefType.BATCH to b.id
        }
        return LibraryKeys(items, refs, references)
    }

    suspend fun meal(text: String, keys: LibraryKeys): AiMealResult =
        call("/ai/meal", json.encodeToString(TextBody.serializer(), TextBody(text, keys.items)), AiMealResult.serializer())

    /** One to four photos of the same meal; several angles help the portion estimate. */
    suspend fun plate(jpegsBase64: List<String>, note: String?, keys: LibraryKeys): AiMealResult =
        call("/ai/plate", json.encodeToString(ImagesBody.serializer(), ImagesBody(jpegsBase64.take(4), text = note, library = keys.items)), AiMealResult.serializer())

    suspend fun product(jpegBase64: String): AiProductResult =
        call("/ai/product", json.encodeToString(ImageBody.serializer(), ImageBody(jpegBase64)), AiProductResult.serializer())

    suspend fun label(jpegBase64: String): AiLabelResult =
        call("/ai/label", json.encodeToString(ImageBody.serializer(), ImageBody(jpegBase64)), AiLabelResult.serializer())

    suspend fun recipe(text: String, keys: LibraryKeys): AiRecipeResult =
        call("/ai/recipe", json.encodeToString(TextBody.serializer(), TextBody(text, keys.items)), AiRecipeResult.serializer())

    /** This month's AI spending and the monthly limit. */
    suspend fun usage(): AiUsage = withContext(Dispatchers.IO) { json.decodeFromString(AiUsage.serializer(), AiTransports.current().get("/ai/usage")) }

    suspend fun setCap(usd: Double) = withContext(Dispatchers.IO) { AiTransports.current().post("/ai/cap", json.encodeToString(CapBody.serializer(), CapBody(usd))) }

    private suspend fun <T> call(path: String, body: String, s: kotlinx.serialization.KSerializer<T>): T = withContext(Dispatchers.IO) {
        val text = AiTransports.current().post(path, body, readTimeoutMs = 60_000)
        json.decodeFromString(Envelope.serializer(s), text).data
    }

    /**
     * Turns AI items into loggable items. A matched item uses the library's numbers; an unmatched one
     * becomes a quick entry from the estimate. [byEye] marks everything as an estimate (plate photos).
     */
    fun resolve(items: List<AiItem>, keys: LibraryKeys, library: Library, byEye: Boolean): List<ResolvedItem> = items.mapNotNull { it ->
        // A reference database dish: real numbers from the database, typical rather than exact.
        keys.references[it.matchKey]?.let { r ->
            val unit = it.unit.takeIf { u -> u in r.units } ?: r.units.first()
            return@mapNotNull ResolvedItem(
                r.name, "reference", r.code, unit, it.qty, r.nutrients(unit, it.qty),
                if (byEye || it.confidence == "low") Basis.ESTIMATE else Basis.TYPICAL, null, it.confidence, it.said, reference = r, brand = it.brand,
            )
        }
        val ref = it.matchKey?.let { k -> keys.refs[k] }
        val units = ref?.let { (t, id) -> library.unitsFor(t, id) }.orEmpty()
        if (ref != null && units.isNotEmpty()) {
            val unit = it.unit.takeIf { u -> u in units } ?: units.first()
            val n = library.entryNutrients(ref.first, ref.second, unit, it.qty)
            val generic = ref.first == RefType.FOOD && library.foods[ref.second]?.kind == FoodKind.GENERIC
            val basis = when {
                byEye || it.confidence == "low" -> Basis.ESTIMATE
                generic -> Basis.TYPICAL
                else -> Basis.EXACT
            }
            ResolvedItem(library.nameOf(ref.first, ref.second) ?: it.name, ref.first, ref.second, unit, it.qty, n, basis, null, it.confidence, it.said, brand = it.brand)
        } else {
            val e = it.estimate ?: return@mapNotNull null
            val per100 = Nutrients(e.kcal, e.protein, e.fibre, e.carbs, e.fat)
            ResolvedItem(it.name, RefType.QUICK, null, "g", e.grams, per100 * (e.grams / 100.0), Basis.ESTIMATE, per100, it.confidence, it.said, brand = it.brand)
        }
    }
}

/** Words that never name a food, skipped when looking for reference dishes. */
private val STOP_WORDS = setOf(
    "and", "with", "the", "for", "some", "had", "ate", "eat", "about", "half", "one", "two", "three", "four", "five",
    "cup", "cups", "bowl", "plate", "glass", "piece", "pieces", "slice", "slices", "tbsp", "tsp", "gram", "grams",
    "lunch", "dinner", "breakfast", "snack", "snacks", "today", "katori", "small", "medium", "large", "big", "little",
    "und", "mit", "eine", "ein", "einen",
)
