package app.shoku.data.ai

import android.content.Context
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.domain.Library
import app.shoku.domain.WEIGHT_UNITS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable private data class UnitsFood(val k: String, val name: String, val unit: String, val brand: String = "")
@Serializable private data class UnitsSubmit(val foods: List<UnitsFood>)
@Serializable private data class UnitsSubmitted(val batchId: String)
@Serializable data class AiPortion(val label: String, val amount: Double)
@Serializable data class AiUnits(val k: String, @SerialName("grams_per_unit") val gramsPerUnit: Double? = null, val portions: List<AiPortion> = emptyList())
@Serializable private data class UnitsResult(val status: String, val foods: List<AiUnits> = emptyList())

/**
 * Gives foods household units ("tbsp", "slice", "glass") with AI, without being in a hurry: foods that
 * have no portions of their own or from their family are sent in one Batch API job (half price), and
 * the answer is collected on a later app start. At most one new job a day.
 */
class UnitsFiller(private val context: Context, private val repo: ShokuRepository) {
    private val prefs = context.getSharedPreferences("units", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun run() = withContext(Dispatchers.IO) {
        if (AiTransports.mode() == AiMode.NONE) return@withContext
        val ai = AiTransports.current()
        val pending = prefs.getString("batchId", null)
        if (pending != null) {
            // A job started elsewhere (the backend, or another API key) can't be fetched from here: drop it.
            if ((prefs.getString("batchVia", null) ?: BackendTransport.id) != ai.id) {
                prefs.edit().remove("batchId").remove("sent").apply()
                return@withContext
            }
            collect(ai, pending)
            return@withContext
        }
        if (System.currentTimeMillis() - prefs.getLong("lastSubmit", 0) < 24 * 3600_000L) return@withContext
        val library = repo.library.first()
        val done = prefs.getStringSet("done", emptySet()).orEmpty()
        val todo = needingUnits(library).filter { it.id !in done }
        if (todo.isEmpty()) return@withContext
        val body = json.encodeToString(UnitsSubmit.serializer(), UnitsSubmit(todo.map { UnitsFood(it.id, it.name, it.unit, it.brand.orEmpty()) }))
        val r = json.decodeFromString(UnitsSubmitted.serializer(), ai.post("/ai/units/submit", body))
        prefs.edit().putString("batchId", r.batchId).putString("batchVia", ai.id).putLong("lastSubmit", System.currentTimeMillis())
            .putStringSet("sent", todo.map { it.id }.toSet()).apply()
    }

    private suspend fun collect(ai: AiTransport, batchId: String) {
        val r = json.decodeFromString(UnitsResult.serializer(), ai.get("/ai/units/result?id=$batchId", readTimeoutMs = 60_000))
        if (r.status != "ended") return
        val library = repo.library.first()
        val rows = r.foods.flatMap { u -> library.foods[u.k]?.let { portionsFor(it, u, library) }.orEmpty() }
        if (rows.isNotEmpty()) repo.savePortions(rows)
        val done = prefs.getStringSet("done", emptySet()).orEmpty() + prefs.getStringSet("sent", emptySet()).orEmpty()
        prefs.edit().remove("batchId").remove("batchVia").remove("sent").putStringSet("done", done).apply()
    }

    companion object {
        /** Foods that can only be logged in their own unit: no portions of their own or from their family. */
        fun needingUnits(library: Library): List<Food> = library.foods.values
            .filter { !it.archived && !it.deleted && library.portionsOf(it).isEmpty() }
            .sortedBy { it.name }

        /** Portion rows for one AI answer; skips the food's own unit and labels it already has. */
        fun portionsFor(food: Food, u: AiUnits, library: Library): List<FoodPortion> {
            val existing = library.portionsOf(food).map { it.label }.toSet() + food.unit
            fun row(label: String, qty: Double) =
                FoodPortion(UUID.nameUUIDFromBytes("portion|${food.id}|$label".toByteArray()).toString(), food.id, label, qty)
            val out = ArrayList<FoodPortion>()
            // Counted foods (pc, serving, can) get grams: 1 g is 1/weight of one piece.
            val grams = u.gramsPerUnit
            if (food.unit !in WEIGHT_UNITS && grams != null && grams > 0 && "g" !in existing) out += row("g", 1.0 / grams)
            u.portions.forEach { p ->
                val label = p.label.trim().lowercase()
                if (label.isNotEmpty() && label.length <= 12 && label !in existing && p.amount > 0 && out.none { it.label == label }) out += row(label, p.amount)
            }
            return out
        }
    }
}
