package app.shoku.data.ai

import app.shoku.data.db.MealTemplate
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Library
import app.shoku.domain.PlanState
import app.shoku.domain.SLOT
import app.shoku.domain.StockLevel
import app.shoku.domain.templateTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/*
 * AI week planning (Plan > Meals > "Plan my week with AI") and per-meal ideas (Meal menu). One
 * request per call (~1-3 cents, counted against the monthly cap); the answer becomes draft templates that
 * are not saved until the user picks them (HomeRepository.saveTemplate / setWeekdays).
 */

@Serializable private data class PlanGoals(val kcal: Double, val protein: Double)
@Serializable private data class PlanBody(
    val goals: PlanGoals,
    val rules: List<String>,
    val library: List<LibItem>,
    val stock: List<String>,
    val current: List<String>,
    val text: String? = null,
    val history: List<String> = emptyList(),
    val meal: String? = null,
)
@Serializable data class AiPlanItem(@SerialName("match_key") val matchKey: String, val unit: String, val qty: Double)
@Serializable data class AiPlanTemplate(val name: String, val meal: String, val weekdays: List<Int>, val items: List<AiPlanItem>)
@Serializable data class AiPlanResult(val templates: List<AiPlanTemplate>, val notes: String)
@Serializable private data class PlanEnvelope(val data: AiPlanResult)

/** A proposed template with its items and days, ready to review; nothing is saved. */
data class DraftTemplate(val template: MealTemplate, val items: List<TemplateItem>, val weekdays: Set<Int>)

/** The proposal: templates, a short explanation, and AI items that didn't match the library (left out). */
data class PlanProposal(val drafts: List<DraftTemplate>, val notes: String, val skipped: Int)

class PlanAi(private val ai: AiClient = AiClient()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Asks for a week plan. [rules] are plain sentences ("no eggs or chicken on Tuesday"), [request] an
     * optional wish ("more fibre at dinner").
     */
    suspend fun propose(
        library: Library,
        plan: PlanState,
        stock: List<StockLevel>,
        goalKcal: Double,
        goalProtein: Double,
        rules: List<String>,
        request: String? = null,
        logs: List<app.shoku.data.db.LogEntry> = emptyList(),
    ): PlanProposal = withContext(Dispatchers.IO) {
        val keys = ai.libraryKeys(library)
        val history = historyLines(library, logs, null)
        val stockLines = stock.filter { it.item.foodId != null }.map { l ->
            val qty = l.qty?.let { "${Math.round(it)} ${l.item.unit}" } ?: l.item.status
            "${l.item.name} $qty" + (l.daysLeft?.let { ", ~${Math.round(it)} days left" } ?: "")
        }
        val current = plan.templates.filter { !it.deleted }.map { t ->
            val n = library.templateTotals(plan.itemsOf(t.id))
            "${t.meal}: ${t.name} (days ${plan.weekdaysOf(t.id).joinToString(",")}; ${Math.round(n.kcal ?: 0.0)} kcal, ${Math.round(n.protein ?: 0.0)} g protein)"
        }
        val body = json.encodeToString(PlanBody.serializer(), PlanBody(PlanGoals(goalKcal, goalProtein), rules, keys.items, stockLines, current, request, history))
        val result = json.decodeFromString(PlanEnvelope.serializer(), AiTransports.current().post("/ai/plan", body, readTimeoutMs = 90_000)).data
        toDrafts(result, keys)
    }

    /**
     * Three new options for one [meal], from what's in stock and what is eaten most (one request,
     * ~2-3 cents). Drafts have no weekdays; nothing is saved.
     */
    suspend fun suggestMeal(
        library: Library,
        plan: PlanState,
        stock: List<StockLevel>,
        logs: List<app.shoku.data.db.LogEntry>,
        goalKcal: Double,
        goalProtein: Double,
        rules: List<String>,
        meal: String,
    ): PlanProposal = withContext(Dispatchers.IO) {
        val keys = ai.libraryKeys(library)
        val history = historyLines(library, logs, meal)
        val stockLines = stock.filter { it.item.foodId != null }.map { l -> "${l.item.name} ${l.qty?.let { "${Math.round(it)} ${l.item.unit}" } ?: l.item.status}" }
        val current = plan.templates.filter { !it.deleted && it.meal == meal }.map { t -> "${t.name}: " + plan.itemsOf(t.id).joinToString(", ") { i -> i.refId?.let { library.nameOf(i.refType, it) } ?: "batch" } }
        val body = json.encodeToString(PlanBody.serializer(), PlanBody(PlanGoals(goalKcal, goalProtein), rules, keys.items, stockLines, current, null, history, meal))
        val result = json.decodeFromString(PlanEnvelope.serializer(), AiTransports.current().post("/ai/plan", body, readTimeoutMs = 90_000)).data
        toDrafts(result, keys)
    }

    /**
     * What is eaten most in the last 90 days, by number of days ("Skyr (14 days)"); for one [meal], its
     * items first and other meals marked as such.
     */
    private fun historyLines(library: Library, logs: List<app.shoku.data.db.LogEntry>, meal: String?): List<String> {
        val from = java.time.LocalDate.now().minusDays(90).toString()
        return logs.filter { !it.deleted && it.status == app.shoku.data.db.EntryStatus.EATEN && it.date >= from && it.refId != null }
            .groupBy { (if (meal == null) "${it.meal}: " else if (it.meal == meal) "" else "other meals: ") + (library.nameOf(it.refType, it.refId!!) ?: it.name) }
            .mapValues { (_, es) -> es.map { it.date }.distinct().size }
            .entries.sortedByDescending { it.value }.take(60).map { "${it.key} (${it.value} days)" }
    }

    /** Maps library keys back to ids; items with unknown keys are dropped and counted. */
    fun toDrafts(result: AiPlanResult, keys: AiClient.LibraryKeys): PlanProposal {
        var skipped = 0
        val drafts = result.templates.mapIndexed { pos, t ->
            val id = UUID.randomUUID().toString()
            val items = t.items.mapIndexedNotNull { i, it ->
                if (it.matchKey == SLOT) {
                    return@mapIndexedNotNull TemplateItem(UUID.randomUUID().toString(), id, SLOT, null, "Sabzi or dal (batch in the fridge)", "serving", it.qty, i)
                }
                val ref = keys.refs[it.matchKey]
                if (ref == null || ref.first == "reference") { skipped++; return@mapIndexedNotNull null }
                TemplateItem(UUID.randomUUID().toString(), id, ref.first, ref.second, "", it.unit, it.qty, i)
            }
            DraftTemplate(MealTemplate(id, t.name, t.meal, pos), items, t.weekdays.filter { it in 1..7 }.toSet())
        }
        return PlanProposal(drafts, result.notes, skipped)
    }
}

