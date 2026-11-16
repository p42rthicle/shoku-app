package app.shoku.data.ai

import android.content.Context
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.ui.formatUnitQty
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * What Shoku learns from you: every time you change what the AI estimated (another food, another amount),
 * the correction is kept, and each describe or photo request sends your recent corrections and usual
 * portions along, so the next estimate follows how you actually eat. Kept on this phone (last 120).
 */
object AiMemory {
    @Serializable data class Correction(val at: Long, val line: String)

    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true }
    fun init(context: Context) { app = context.applicationContext }

    private val prefs get() = app.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    fun all(): List<Correction> = runCatching {
        json.decodeFromString(ListSerializer(Correction.serializer()), prefs.getString("corrections", "[]")!!)
    }.getOrDefault(emptyList())

    private fun record(line: String) {
        val kept = (all().filter { it.line != line } + Correction(System.currentTimeMillis(), line)).takeLast(120)
        prefs.edit().putString("corrections", json.encodeToString(ListSerializer(Correction.serializer()), kept)).apply()
    }

    private fun amount(qty: Double, unit: String) = formatUnitQty(qty, unit)

    /** The review: what the AI proposed ([before], by its "said") against what was logged. */
    fun learnFromReview(before: List<ResolvedItem>, after: List<ResolvedItem>) {
        if (!::app.isInitialized) return
        after.forEach { now ->
            val was = before.firstOrNull { it.said == now.said } ?: return@forEach
            val sameFood = was.refType == now.refType && was.refId == now.refId && was.name == now.name
            val sameAmount = was.unit == now.unit && Math.abs(was.qty - now.qty) < 1e-6
            if (sameFood && sameAmount) return@forEach
            val heard = now.said.ifBlank { was.name }
            record(
                if (sameFood) "\"$heard\": ${now.name} is ${amount(now.qty, now.unit)}, not ${amount(was.qty, was.unit)}"
                else "\"$heard\" is ${now.name} (${amount(now.qty, now.unit)}), not ${was.name} (${amount(was.qty, was.unit)})",
            )
        }
    }

    /** An entry the AI made, changed later on Today. */
    fun learnFromEdit(old: LogEntry, new: LogEntry) {
        if (!::app.isInitialized || old.source != "ai") return
        val sameFood = old.refType == new.refType && old.refId == new.refId
        val sameAmount = old.unit == new.unit && Math.abs(old.qty - new.qty) < 1e-6
        if (sameFood && sameAmount) return
        record(
            if (sameFood) "${new.name}: ${amount(new.qty, new.unit)}, not ${amount(old.qty, old.unit)}"
            else "${old.name} (${amount(old.qty, old.unit)}) was really ${new.name} (${amount(new.qty, new.unit)})",
        )
    }

    /**
     * Lines for the AI: the 25 latest corrections, then the usual amount of the 20 foods logged most in the
     * last 60 days ("Boiled Rice: usually 150 g").
     */
    fun hints(logs: List<LogEntry>, today: LocalDate = LocalDate.now()): List<String> {
        val corrections = if (::app.isInitialized) all().takeLast(25).reversed().map { it.line } else emptyList()
        val since = today.minusDays(60).toString()
        val usual = logs.asSequence()
            .filter { !it.deleted && it.date >= since && it.refType != RefType.QUICK }
            .groupBy { it.name }
            .filterValues { it.size >= 3 }
            .entries.sortedByDescending { it.value.size }.take(20)
            .map { (name, list) ->
                val (unit, qty) = list.groupingBy { it.unit to it.qty }.eachCount().maxBy { it.value }.key
                "$name: usually ${amount(qty, unit)} (${list.size}× in 2 months)"
            }
        return corrections + usual
    }
}
