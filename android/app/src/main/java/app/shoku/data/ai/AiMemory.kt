package app.shoku.data.ai

import android.content.Context
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.domain.searchKey
import app.shoku.ui.formatUnitQty
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.ln

/**
 * What Shoku learns from you, kept on this phone (up to 500 memories) and sent as notes with each describe
 * or photo request. Nothing is trained: Claude reads the notes and follows them.
 *
 * - A **fix**: you changed what the AI proposed (another food or amount), in the review or later on Today.
 * - A **match**: the AI matched something to your own food and you kept it ("rice" is your Boiled Rice).
 *
 * The same memory again counts up and takes your latest amount. Each request gets the memories that fit it:
 * words shared with what you typed, the same meal, recent and repeated ones first; plus your usual amounts
 * at that meal and the batches in your fridge.
 */
object AiMemory {
    @Serializable
    data class Memory(
        val key: String,
        val words: List<String>,
        val line: String,
        val kind: String,
        val meal: String? = null,
        val count: Int = 1,
        val at: Long = System.currentTimeMillis(),
    )

    const val FIX = "fix"
    const val MATCH = "match"
    private const val KEEP = 500

    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true }
    fun init(context: Context) { app = context.applicationContext }
    private val ready get() = ::app.isInitialized
    private val prefs get() = app.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    private val STOP = setOf("the", "and", "with", "some", "one", "two", "half", "plate", "bowl", "katori", "cup", "glass", "piece", "pieces", "small", "medium", "large", "raw", "cooked")

    /** Words that identify a food in what someone types or a name ("2 katori rajma" -> rajma). */
    fun words(vararg texts: String?): List<String> = texts.filterNotNull().flatMap { searchKey(it).split(Regex("[^a-z]+")) }
        .filter { it.length > 2 && it !in STOP }.map { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }.distinct()

    fun all(): List<Memory> {
        if (!ready) return emptyList()
        prefs.getString("memories", null)?.let { s -> return runCatching { json.decodeFromString(ListSerializer(Memory.serializer()), s) }.getOrDefault(emptyList()) }
        // Version 0.9.4 kept plain lines ("corrections"): bring them over as fixes.
        return runCatching {
            Json.parseToJsonElement(prefs.getString("corrections", "[]")!!).let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val line = (o["line"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                val at = (o["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: System.currentTimeMillis()
                Memory(line, words(line), line, FIX, at = at)
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Memory>) {
        prefs.edit().putString("memories", json.encodeToString(ListSerializer(Memory.serializer()), list.sortedBy { it.at }.takeLast(KEEP))).apply()
    }

    /** Adds [m], or strengthens the same memory (same key): count + 1, latest wording and time. */
    private fun remember(m: Memory) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.key == m.key }
        if (i >= 0) list[i] = m.copy(count = list[i].count + 1) else list += m
        // A fix replaces a match for the same words ("rice" was matched to basmati, then corrected).
        // (Keys are "kind|[heard words]|food": the same heard words, another answer.)
        if (m.kind == FIX) list.removeAll { it.kind == MATCH && it.key.split('|').getOrNull(1) == m.key.split('|').getOrNull(1) }
        save(list)
    }

    private fun amount(qty: Double, unit: String) = formatUnitQty(qty, unit)

    /** The review: what the AI proposed ([before], by its "said") against what was logged ([after]). */
    fun learnFromReview(before: List<ResolvedItem>, after: List<ResolvedItem>, meal: String? = null) {
        if (!ready) return
        after.forEach { now ->
            val was = before.firstOrNull { it.said == now.said } ?: return@forEach
            val heard = now.said.ifBlank { was.name }
            val sameFood = was.refType == now.refType && was.refId == now.refId && was.name == now.name
            val sameAmount = was.unit == now.unit && Math.abs(was.qty - now.qty) < 1e-6
            when {
                // Kept as proposed, and it's one of their own foods: remember the match.
                sameFood && sameAmount -> if (now.refType != RefType.QUICK && now.reference == null && now.refId != null) remember(
                    Memory("match|${words(heard).sorted()}|${now.refId}", words(heard, now.name), "\"$heard\" is their ${now.name} (${amount(now.qty, now.unit)})", MATCH, meal),
                )
                sameFood -> remember(Memory("amount|${now.refId ?: now.name}|$meal", words(heard, now.name),
                    "\"$heard\": ${now.name} is ${amount(now.qty, now.unit)}, not ${amount(was.qty, was.unit)}", FIX, meal))
                else -> remember(Memory("food|${words(heard).sorted()}|${now.refId ?: now.name}", words(heard, now.name, was.name),
                    "\"$heard\" is ${now.name} (${amount(now.qty, now.unit)}), not ${was.name} (${amount(was.qty, was.unit)})", FIX, meal))
            }
        }
    }

    /** An entry the AI made, changed later on Today. */
    fun learnFromEdit(old: LogEntry, new: LogEntry) {
        if (!ready || old.source != "ai") return
        val sameFood = old.refType == new.refType && old.refId == new.refId
        val sameAmount = old.unit == new.unit && Math.abs(old.qty - new.qty) < 1e-6
        if (sameFood && sameAmount) return
        remember(
            if (sameFood) Memory("amount|${new.refId ?: new.name}|${new.meal}", words(new.name), "${new.name}: ${amount(new.qty, new.unit)}, not ${amount(old.qty, old.unit)}", FIX, new.meal)
            else Memory("food|${words(old.name).sorted()}|${new.refId ?: new.name}", words(old.name, new.name),
                "${old.name} (${amount(old.qty, old.unit)}) was really ${new.name} (${amount(new.qty, new.unit)})", FIX, new.meal),
        )
    }

    /**
     * Notes for one request: the memories that fit it best ([query]: what they typed or noted; [meal]), then
     * their usual amounts at that meal, then the batches in their fridge ([fridge]).
     */
    fun hints(logs: List<LogEntry>, query: String? = null, meal: String? = null, fridge: List<String> = emptyList(), today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): List<String> {
        val q = words(query).toSet()
        val picked = all().map { m ->
            val overlap = m.words.count { it in q }
            val days = (now - m.at) / 86_400_000.0
            val score = 3.0 * overlap +
                (if (meal != null && m.meal == meal) 1.0 else 0.0) +
                1.0 / (1.0 + days / 30.0) +
                0.5 * ln(m.count.toDouble()) +
                (if (m.kind == FIX) 1.0 else 0.3)
            m to score
        }
            // With something typed, memories about other foods only get in on strength (recent, repeated, same meal).
            .filter { (m, s) -> q.isEmpty() || m.words.any { it in q } || s >= 2.0 }
            .sortedByDescending { it.second }.take(25).map { it.first.line }

        val since = today.minusDays(60).toString()
        val usual = logs.asSequence()
            .filter { !it.deleted && it.date >= since && it.refType != RefType.QUICK && (meal == null || it.meal == meal) }
            .groupBy { it.name }
            .filterValues { it.size >= 3 }
            .entries.sortedByDescending { it.value.size }.take(if (meal == null) 20 else 12)
            .map { (name, list) ->
                val (unit, qty) = list.groupingBy { it.unit to it.qty }.eachCount().maxBy { it.value }.key
                (if (meal != null) "At ${meal.lowercase()}: " else "") + "$name, usually ${amount(qty, unit)} (${list.size}× in 2 months)"
            }
        val inFridge = if (fridge.isEmpty()) emptyList() else listOf("Cooked and in their fridge now (likely on the plate): ${fridge.joinToString(", ")}")
        return picked + inFridge + usual
    }
}
