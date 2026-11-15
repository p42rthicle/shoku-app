package app.shoku.domain

import app.shoku.data.db.EntryStatus
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import java.time.LocalDate

/** One item of a meal you actually ate, with its usual amount. [refType] SLOT stands for "a batch". */
data class HistoryItem(val refType: String, val refId: String?, val unit: String, val qty: Double)

/** A meal you've eaten more than once, as a template-to-be. */
data class HistoryMeal(val meal: String, val name: String, val items: List<HistoryItem>, val times: Int, val lastDate: String)

/**
 * Meals worth turning into templates, from the last [days] of eating: your usual core for that meal
 * (items on at least 40% of its days), then exact combinations eaten at least twice. Batches count as
 * "a batch" (they change with each cook); quick entries are left out. Combinations matching an
 * existing template are skipped.
 */
fun Library.mealHistory(logs: List<LogEntry>, meal: String, today: LocalDate, plan: PlanState, days: Long = 120, limit: Int = 6): List<HistoryMeal> {
    val from = today.minusDays(days).toString()
    val eaten = logs.filter { !it.deleted && it.status == EntryStatus.EATEN && it.meal == meal && it.date >= from && it.refType != RefType.QUICK && it.refId != null }
    fun key(e: LogEntry) = if (e.refType == RefType.BATCH) SLOT else "${e.refType}:${e.refId}"
    val byDay = eaten.groupBy { it.date }
    if (byDay.isEmpty()) return emptyList()

    fun amounts(keys: Collection<String>, dates: Set<String>): List<HistoryItem> = keys.mapNotNull { k ->
        val es = eaten.filter { key(it) == k && it.date in dates }
        if (es.isEmpty()) return@mapNotNull null
        if (k == SLOT) return@mapNotNull HistoryItem(SLOT, null, PortionMetrics.SERVING, 1.0)
        val (unit, qty) = es.groupingBy { it.unit to it.qty }.eachCount().maxByOrNull { it.value }!!.key
        HistoryItem(es.first().refType, es.first().refId, unit, qty)
    }
    fun name(items: List<HistoryItem>) = items.take(3).joinToString(", ") { i -> if (i.refType == SLOT) "batch" else i.refId?.let { nameOf(i.refType, it) } ?: "?" }
    val existing = plan.templates.filter { !it.deleted && it.meal == meal }.map { t ->
        plan.itemsOf(t.id).map { if (it.refType == SLOT) SLOT else "${it.refType}:${it.refId}" }.toSet()
    }.toSet()

    val out = ArrayList<HistoryMeal>()
    // The usual core.
    val counts = byDay.values.flatMap { d -> d.map(::key).distinct() }.groupingBy { it }.eachCount()
    val core = counts.filter { it.value >= byDay.size * 0.4 && byDay.size >= 3 }.keys
    if (core.size >= 2 && core !in existing) {
        val items = amounts(core, byDay.keys)
        out += HistoryMeal(meal, "Usual: " + name(items), items, byDay.values.count { d -> d.map(::key).containsAll(core) }, byDay.keys.max())
    }
    // Exact combinations eaten at least twice.
    byDay.entries.groupBy { (_, d) -> d.map(::key).toSortedSet() }
        .filter { (set, ds) -> ds.size >= 2 && set.size >= 2 && set !in existing && set != core.toSortedSet() }
        .entries.sortedWith(compareByDescending<Map.Entry<java.util.SortedSet<String>, List<Map.Entry<String, List<LogEntry>>>>> { it.value.size }.thenByDescending { it.value.maxOf { d -> d.key } })
        .forEach { (set, ds) ->
            val items = amounts(set, ds.map { it.key }.toSet())
            out += HistoryMeal(meal, name(items), items, ds.size, ds.maxOf { it.key })
        }
    return out.take(limit)
}
