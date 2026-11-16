package app.shoku.data.ai

import android.content.Context
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.domain.WEIGHT_UNITS
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
 * - A **fix**: you changed what the AI proposed (another food or amount), in the review or later on Today:
 *   "Crushed Gobhi Soya Paneer: AI said 200 g, it was 1 serving (~150 g)".
 * - A **match**: the AI matched something to your own food and you kept it ("rice" is your Boiled Rice).
 *
 * One memory per food (key "food|<id>"): what you changed last is what it keeps. A fix outranks a match for the
 * same food (keeping the AI's next guess doesn't undo your correction); a newer fix replaces the older one. Each request gets the memories that fit it:
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
        /** Fixes and matches (since 0.9.44): what was heard, the food and amount kept, and what the AI said. */
        val heard: String? = null,
        val name: String? = null,
        val amount: String? = null,
        val aiFood: String? = null,
        val aiAmount: String? = null,
    ) {
        /** The note as Claude (and the memory view) reads it. */
        fun rendered(): Memory = copy(line = when {
            name == null || amount == null -> line
            kind == MATCH -> (if (heard != null) "\"$heard\" is their " else "Their ") + "$name ($amount)"
            kind == FIX && aiFood != null && aiFood != name -> (if (heard != null && heard != aiFood) "\"$heard\": " else "") + "AI said $aiFood ($aiAmount), it was $name ($amount)"
            kind == FIX -> "$name: AI said $aiAmount, it was $amount"
            else -> line
        })
    }

    const val FIX = "fix"
    const val MATCH = "match"
    /** What their home plates, bowls and cutlery look like (learned from photos tagged At home). */
    const val DISH = "dish"
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

    private fun heardKey(m: Memory) = m.heard?.let { words(it).sorted() }

    /**
     * Adds [m], or updates the memory about the same food (same key), count + 1:
     * - a match after a fix keeps the fix (your correction stands);
     * - a newer fix replaces the amount, keeping which food the AI first mistook it for.
     */
    private fun remember(m: Memory) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.key == m.key }
        if (i >= 0) {
            val old = list[i]
            list[i] = when {
                m.kind == MATCH && old.kind == FIX -> old.copy(count = old.count + 1, at = m.at, meal = m.meal ?: old.meal)
                m.kind == FIX && old.kind == FIX && m.aiFood == null && old.aiFood != null ->
                    m.copy(aiFood = old.aiFood, aiAmount = old.aiAmount, heard = m.heard ?: old.heard, words = (old.words + m.words).distinct(), count = old.count + 1).rendered()
                else -> m.copy(heard = m.heard ?: old.heard, words = (old.words + m.words).distinct(), count = old.count + 1).rendered()
            }
        } else list += m.rendered()
        // A fix replaces a match for the same words about another food ("rice" was matched to basmati, then corrected).
        if (m.kind == FIX) heardKey(m)?.let { h -> list.removeAll { it.kind == MATCH && it.key != m.key && heardKey(it) == h } }
        save(list)
    }

    /** "1 serving (~150 g)": a household amount with its weight, when the other side was weighed (by calories). */
    private fun withWeight(qty: Double, unit: String, kcal: Double?, otherQty: Double, otherUnit: String, otherKcal: Double?): String {
        val base = amount(qty, unit)
        if (unit in WEIGHT_UNITS || otherUnit !in WEIGHT_UNITS || kcal == null || otherKcal == null || kcal <= 0 || otherKcal <= 0) return base
        return "$base (~${roughly(otherQty * kcal / otherKcal)} $otherUnit)"
    }

    private fun roughly(v: Double): String = (if (v >= 50) Math.round(v / 5) * 5 else Math.round(v)).toString()

    private fun foodKey(refId: String?, name: String) = "food|${refId ?: name}"

    /** A fix: [name] was [amount]; the AI said [aiFood] ([aiAmount]). */
    private fun fix(refId: String?, name: String, amount: String, aiFood: String?, aiAmount: String, heard: String?, meal: String?) =
        Memory(foodKey(refId, name), words(heard, name, aiFood), "", FIX, meal, heard = heard, name = name, amount = amount, aiFood = aiFood, aiAmount = aiAmount)

    /**
     * One time (0.9.44): memories in the older shape become one per food, re-worded "AI said …, it was …".
     * [weigh] gives the weight of an amount of a food ("1 serving" of the gobhi batch -> "150 g"), from the library.
     */
    fun upgrade(weigh: ((foodId: String, qty: Double, unit: String) -> String?)? = null) {
        if (!ready || prefs.getBoolean("v2", false)) return
        val amt = Regex("^(\\d+(?:\\.\\d+)?) (.+)$")
        fun weighed(id: String?, a: String): String {
            val m = amt.find(a) ?: return a
            val unit = m.groupValues[2].let { if (it == "servings") "serving" else it }
            if (unit in WEIGHT_UNITS || id == null) return a
            return weigh?.invoke(id, m.groupValues[1].toDouble(), unit)?.let { "$a (~$it)" } ?: a
        }
        val parsed = all().map { m ->
            if (m.kind == DISH || m.name != null) return@map m
            val parts = m.key.split('|')
            val id = when (parts.firstOrNull()) { "match", "food" -> parts.lastOrNull(); "amount" -> parts.getOrNull(1); else -> null }
            fun r(re: String) = Regex(re).find(m.line)?.groupValues
            val shaped = r("^\"(.+)\" is their (.+) \\((.+)\\)$")?.let { g -> m.copy(heard = g[1], name = g[2], amount = g[3]) }
                ?: r("^\"(.+)\": (.+) is (.+), not (.+)$")?.let { g -> m.copy(heard = g[1], name = g[2], amount = weighed(id, g[3]), aiAmount = g[4]) }
                ?: r("^\"(.+)\" is (.+) \\((.+)\\), not (.+) \\((.+)\\)$")?.let { g -> m.copy(heard = g[1], name = g[2], amount = g[3], aiFood = g[4], aiAmount = g[5]) }
                ?: r("^(.+) \\((.+)\\) was really (.+) \\((.+)\\)$")?.let { g -> m.copy(name = g[3], amount = g[4], aiFood = g[1], aiAmount = g[2]) }
                ?: r("^(.+): (.+), not (.+)$")?.let { g -> m.copy(name = g[1], amount = weighed(id, g[2]), aiAmount = g[3]) }
            (shaped ?: m).let { s -> if (s.name != null) s.copy(key = foodKey(id, s.name)).rendered() else s }
        }
        // One per food: a fix over a match, then the newest; counts add up.
        val merged = parsed.groupBy { it.key }.values.map { g ->
            val keep = g.sortedWith(compareByDescending<Memory> { it.kind == FIX }.thenByDescending { it.at }).first()
            keep.copy(count = g.sumOf { it.count }, words = g.flatMap { it.words }.distinct(), heard = keep.heard ?: g.firstNotNullOfOrNull { it.heard })
        }
        save(merged)
        prefs.edit().putBoolean("v2", true).apply()
    }

    /** Forgets one memory (Settings › What Shoku AI has learned). */
    fun forget(key: String) { if (ready) save(all().filter { it.key != key }) }

    /** Puts a forgotten memory back (Undo). */
    fun restore(m: Memory) { if (ready) save(all().filter { it.key != m.key } + m) }

    /** Forgets everything learned (usual amounts and the fridge still come from the log). */
    fun forgetAll() { if (ready) prefs.edit().putString("memories", "[]").remove("corrections").apply() }

    private fun amount(qty: Double, unit: String) = formatUnitQty(qty, unit)

    /** The review: what the AI proposed ([before], by its "said") against what was logged ([after]). */
    fun learnFromReview(before: List<ResolvedItem>, after: List<ResolvedItem>, meal: String? = null) {
        if (!ready) return
        after.forEach { now ->
            val was = before.firstOrNull { it.said == now.said } ?: return@forEach
            val heard = now.said.ifBlank { was.name }
            val sameFood = was.refType == now.refType && was.refId == now.refId && was.name == now.name
            val sameAmount = was.unit == now.unit && Math.abs(was.qty - now.qty) < 1e-6
            val nowKcal = now.nutrients?.kcal; val wasKcal = was.nutrients?.kcal
            when {
                // Kept as proposed, and it's one of their own foods: remember the match.
                sameFood && sameAmount -> if (now.refType != RefType.QUICK && now.reference == null && now.refId != null) remember(
                    Memory(foodKey(now.refId, now.name), words(heard, now.name), "", MATCH, meal, heard = heard, name = now.name, amount = amount(now.qty, now.unit)),
                )
                sameFood -> remember(fix(now.refId, now.name, withWeight(now.qty, now.unit, nowKcal, was.qty, was.unit, wasKcal), null,
                    withWeight(was.qty, was.unit, wasKcal, now.qty, now.unit, nowKcal), heard, meal))
                else -> remember(fix(now.refId, now.name, amount(now.qty, now.unit), was.name, amount(was.qty, was.unit), heard, meal))
            }
        }
    }

    /** A photo logged as At home: remember its dishware ("grey matte plate with dark rim, steel spoon"). */
    fun learnHomeDishes(dishware: String) {
        if (!ready || dishware.isBlank()) return
        val w = words(dishware)
        // A description much like one already kept strengthens it instead of adding another.
        val same = all().firstOrNull { it.kind == DISH && (it.words.toSet() intersect w.toSet()).size * 2 >= minOf(it.words.size, w.size).coerceAtLeast(1) }
        remember(Memory(same?.key ?: "dish|${w.sorted()}", w, "Home dishes: ${dishware.trim()}", DISH))
    }

    /** An entry the AI made, changed later on Today. */
    fun learnFromEdit(old: LogEntry, new: LogEntry) {
        if (!ready || old.source != "ai") return
        val sameFood = old.refType == new.refType && old.refId == new.refId
        val sameAmount = old.unit == new.unit && Math.abs(old.qty - new.qty) < 1e-6
        if (sameFood && sameAmount) return
        // Another food: the match kept in the review for the old one ("tea" is their Black tea) no longer holds.
        if (!sameFood) foodKey(old.refId, old.name).let { k -> save(all().filter { !(it.kind == MATCH && it.key == k) }) }
        remember(
            if (sameFood) fix(new.refId, new.name, withWeight(new.qty, new.unit, new.kcal, old.qty, old.unit, old.kcal), null,
                withWeight(old.qty, old.unit, old.kcal, new.qty, new.unit, new.kcal), null, new.meal)
            else fix(new.refId, new.name, amount(new.qty, new.unit), old.name, amount(old.qty, old.unit), null, new.meal),
        )
    }

    /**
     * Notes for one request: the memories that fit it best ([query]: what they typed or noted; [meal]), then
     * their usual amounts at that meal, then the batches in their fridge ([fridge]).
     */
    fun hints(logs: List<LogEntry>, query: String? = null, meal: String? = null, fridge: List<String> = emptyList(), today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): List<String> {
        val q = words(query).toSet()
        val mem = all()
        // Their home dishware always goes along: food on these is home food, usually from the fridge.
        val dishes = mem.filter { it.kind == DISH }.sortedByDescending { it.count }.take(6).map { it.line.removePrefix("Home dishes: ") }
        val dishLine = if (dishes.isEmpty()) emptyList() else listOf("Their home dishes: ${dishes.joinToString("; ")}. Food on these is home food, usually from their fridge batches and usual home portions; a takeaway box, foil or a restaurant plate means food from outside.")
        val picked = mem.filter { it.kind != DISH }.map { m ->
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
        return dishLine + picked + inFridge + usual
    }
}
