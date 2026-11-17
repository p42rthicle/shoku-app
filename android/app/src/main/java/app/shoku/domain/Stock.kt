package app.shoku.domain

import app.shoku.data.db.EntryStatus
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.SupplementDose
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How much of each food (in the food's own unit) an eaten entry used: foods directly, dishes through their ingredients. */
fun Library.foodAmounts(entry: LogEntry): List<Pair<String, Double>> {
    val id = entry.refId ?: return emptyList()
    return when (entry.refType) {
        RefType.FOOD -> listOfNotNull(inFoodUnit(id, entry.unit, entry.qty)?.let { id to it })
        RefType.DISH -> dishes[id]?.let { d ->
            val m = dishMetrics(d)
            val fraction = when {
                entry.unit == PortionMetrics.SERVING -> entry.qty / m.servings
                m.hasWeightBasis && entry.unit == m.finalUnit -> entry.qty / m.finalQty!!
                else -> return@let emptyList()
            }
            dishIngredients[id].orEmpty().filter { !it.deleted && it.refType == RefType.FOOD }
                .mapNotNull { ing -> inFoodUnit(ing.refId, ing.unit, ing.qty * fraction)?.let { ing.refId to it } }
        }.orEmpty()
        else -> emptyList() // batches are used up when cooked, not when eaten
    }
}

/** What cooking a batch took from stock. */
fun Library.batchAmounts(batchId: String): List<Pair<String, Double>> =
    batchIngredients[batchId].orEmpty().filter { !it.deleted && it.refType == RefType.FOOD }
        .mapNotNull { ing -> inFoodUnit(ing.refId, ing.unit, ing.qty)?.let { ing.refId to it } }

/** [qty] of [unit] in the food's own unit (via its portions); null if the unit doesn't fit. */
fun Library.inFoodUnit(foodId: String, unit: String, qty: Double): Double? {
    val f = foods[foodId] ?: return null
    return if (unit == f.unit) qty else portionsOf(f).firstOrNull { it.label == unit }?.qty?.times(qty)
}

/** A food's family key: its generic, or itself. Stock of any family member covers the others. */
fun Library.familyKey(foodId: String): String = familyOf(foodId)?.id ?: foodId

/** One stock item now: current amount, use per day, days left. */
data class StockLevel(val item: StockItem, val qty: Double?, val perDay: Double?, val daysLeft: Double?)

/**
 * Current stock for every item. Each use after an item's count is taken from the item for that exact
 * food, or else from an item of the same family and unit (earliest best-before first).
 */
fun Library.stockLevels(
    items: List<StockItem>,
    moves: List<StockMove>,
    logs: List<LogEntry>,
    doses: List<SupplementDose>,
    plan: PlanState,
    today: LocalDate,
): List<StockLevel> {
    val live = items.filter { !it.deleted }
    val zone = ZoneId.systemDefault()
    fun countDay(i: StockItem) = Instant.ofEpochMilli(i.countedAt).atZone(zone).toLocalDate().toString()
    val used = HashMap<String, Double>()

    fun target(foodId: String): StockItem? {
        val unit = foods[foodId]?.unit
        val key = familyKey(foodId)
        return live.firstOrNull { it.foodId == foodId && it.tracked }
            ?: live.filter { it.tracked && it.foodId != null && it.unit == unit && familyKey(it.foodId) == key }
                .minWithOrNull(compareBy<StockItem>({ it.bestBefore ?: "9999" }, { it.countedAt }))
    }
    fun use(foodId: String, amount: Double, day: String, at: Long) {
        val t = target(foodId) ?: return
        if (day < countDay(t) || at <= t.countedAt) return
        used[t.id] = (used[t.id] ?: 0.0) + amount
    }

    logs.filter { !it.deleted && it.status == EntryStatus.EATEN }.forEach { e -> foodAmounts(e).forEach { (f, a) -> use(f, a, e.date, e.updatedAt) } }
    batches.values.filter { !it.deleted }.forEach { b -> batchAmounts(b.id).forEach { (f, a) -> use(f, a, b.date, b.updatedAt) } }

    val perDay = dailyUse(logs, plan, today)
    return live.map { item ->
        when {
            !item.tracked || item.countQty == null -> StockLevel(item, null, null, null)
            item.supplementId != null -> StockLevel(item, supplementStock(item, doses, moves), null, null)
            else -> {
                val added = moves.filter { it.itemId == item.id && !it.deleted && it.at > item.countedAt }.sumOf { it.delta }
                val qty = (item.countQty - (used[item.id] ?: 0.0) + added).coerceAtLeast(0.0)
                val rate = item.foodId?.let { perDay[familyKey(it)] }
                StockLevel(item, qty, rate, rate?.takeIf { it > 0 }?.let { qty / it })
            }
        }
    }
}

/**
 * Use per day by food family, in the food's unit: the plan's average when the food is in the plan,
 * otherwise what was eaten in the last 28 days.
 */
fun Library.dailyUse(logs: List<LogEntry>, plan: PlanState, today: LocalDate): Map<String, Double> {
    val planned = HashMap<String, Double>()
    (1..7).forEach { day ->
        app.shoku.data.db.Meals.ALL.forEach { meal ->
            plan.templateFor(day, meal)?.let { t ->
                plan.itemsOf(t.id).forEach { i ->
                    if (i.refType == RefType.FOOD && i.refId != null) inFoodUnit(i.refId, i.unit, i.qty)?.let { a ->
                        val k = familyKey(i.refId); planned[k] = (planned[k] ?: 0.0) + a / 7.0
                    }
                }
            }
        }
    }
    val from = today.minusDays(28).toString()
    val eaten = HashMap<String, Double>()
    logs.filter { !it.deleted && it.status == EntryStatus.EATEN && it.date >= from && it.date <= today.toString() }
        .forEach { e -> foodAmounts(e).forEach { (f, a) -> val k = familyKey(f); eaten[k] = (eaten[k] ?: 0.0) + a / 28.0 } }
    batches.values.filter { !it.deleted && it.date >= from }.forEach { b -> batchAmounts(b.id).forEach { (f, a) -> val k = familyKey(f); eaten[k] = (eaten[k] ?: 0.0) + a / 28.0 } }
    return eaten + planned
}

/** Something to buy, and why. */
data class ShoppingItem(val name: String, val reason: String, val item: StockItem?, val foodId: String?) {
    /** Dismissing hides this exact name and reason; a new reason ("2 days left" tomorrow) shows it again. */
    val key get() = "$name|$reason"
}

/**
 * The shopping list: tracked items that run out within [horizonDays] or are under their low mark,
 * status items marked low or out, and foods in the plan with no stock at home.
 */
fun Library.shoppingList(levels: List<StockLevel>, plan: PlanState, horizonDays: Int = 4): List<ShoppingItem> {
    val out = ArrayList<ShoppingItem>()
    levels.filter { it.item.supplementId == null }.forEach { l ->
        val i = l.item
        when {
            !i.tracked && i.status in setOf("low", "out") -> out += ShoppingItem(i.name, if (i.status == "out") "Out" else "Running low", i, i.foodId)
            l.qty != null && l.qty <= 0.0 -> out += ShoppingItem(i.name, "Used up", i, i.foodId)
            l.qty != null && i.lowAt != null && l.qty <= i.lowAt -> out += ShoppingItem(i.name, "Below ${formatAmountPlain(i.lowAt)} ${i.unit}", i, i.foodId)
            l.daysLeft != null && l.daysLeft < horizonDays -> out += ShoppingItem(i.name, if (l.daysLeft < 1) "Runs out today" else Math.round(l.daysLeft).let { d -> "About $d ${if (d == 1L) "day" else "days"} left" }, i, i.foodId)
        }
    }
    val stocked = levels.mapNotNull { it.item.foodId?.let(::familyKey) }.toSet()
    plan.templates.filter { !it.deleted && plan.weekdaysOf(it.id).isNotEmpty() }.flatMap { plan.itemsOf(it.id) }
        .mapNotNull { it.refId?.takeIf { _ -> it.refType == RefType.FOOD } }.distinct()
        .filter { familyKey(it) !in stocked }
        .forEach { id -> foods[id]?.let { out += ShoppingItem(it.name, "In your plan, none at home", null, id) } }
    return out.distinctBy { it.name }
}

/**
 * What's at home of each food, for its Edit food sheet: "350 g in stock · Pantry", "in stock · Spices",
 * "used up". A generic ("any brand") shows its family's stock.
 */
fun Library.stockTexts(levels: List<StockLevel>): Map<String, String> {
    val food = levels.filter { it.item.supplementId == null && it.item.foodId != null }
    fun text(ls: List<StockLevel>): String? {
        val places = ls.map { it.item.place }.distinct().joinToString(", ")
        val tracked = ls.filter { it.item.tracked && it.qty != null }
        if (tracked.isNotEmpty()) {
            val amounts = tracked.groupBy { it.item.unit }.mapNotNull { (u, l) ->
                val q = l.sumOf { it.qty!!.coerceAtLeast(0.0) }
                if (q <= 0.0) null else app.shoku.ui.formatUnitQty(if (q >= 10) Math.round(q).toDouble() else Math.round(q * 10) / 10.0, u)
            }
            return if (amounts.isEmpty()) "Used up · $places" else "${amounts.joinToString(" + ")} in stock · $places"
        }
        val st = ls.map { it.item.status }
        return when { "have" in st -> "In stock · $places"; "low" in st -> "Running low · $places"; "out" in st -> "Out · $places"; else -> null }
    }
    val out = HashMap<String, String>()
    food.groupBy { it.item.foodId!! }.forEach { (id, ls) -> text(ls)?.let { out[id] = it } }
    food.groupBy { familyKey(it.item.foodId!!) }.forEach { (fam, ls) -> if (fam !in out && foods[fam]?.kind == app.shoku.data.db.FoodKind.GENERIC) text(ls)?.let { out[fam] = it } }
    return out
}

/** The weekly list row's key for hiding it: that food, that week. */
fun WeekItem.hideKey(from: LocalDate) = "week|$foodId|$from"

/**
 * When each stock item, and each food family in stock, last changed by your hand: added or bought (a
 * positive move), recounted or edited. Eating doesn't count. A row hidden from To buy or For next week
 * shows again once its stock changes after it was hidden (bought, updated, used up and recounted).
 * Keys: "item:<stock item id>" and "food:<family key>".
 */
fun Library.stockChangedAt(items: List<app.shoku.data.db.StockItem>, moves: List<app.shoku.data.db.StockMove>): Map<String, Long> {
    val lastAdd = moves.filter { !it.deleted && it.delta > 0 }.groupBy { it.itemId }.mapValues { (_, m) -> m.maxOf { it.at } }
    val out = HashMap<String, Long>()
    items.filter { !it.deleted }.forEach { i ->
        val t = maxOf(i.updatedAt, i.countedAt, lastAdd[i.id] ?: 0L)
        out["item:${i.id}"] = t
        i.foodId?.let { f -> val k = "food:${familyKey(f)}"; out[k] = maxOf(out[k] ?: 0L, t) }
    }
    return out
}

/** Whether a row hidden at [hiddenAt] stays hidden: nothing about its stock changed since. */
fun stillHidden(hiddenAt: Long?, changedAt: Long?): Boolean = hiddenAt != null && (changedAt == null || changedAt <= hiddenAt)

private fun formatAmountPlain(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)

/** One line of the weekly shopping list: what the plan needs, what's at home, what to buy. */
data class WeekItem(
    val foodId: String,
    val name: String,
    val unit: String,
    val need: Double,
    val have: Double,
    /** When the plan always uses one portion ("small" apple = 149 g): its label and size in [unit]. */
    val portion: Pair<String, Double>? = null,
) {
    val buy: Double get() = (need - have).coerceAtLeast(0.0)
    /** Rounded up to something you can buy: whole portions or pieces, else 50 g / 50 ml steps. */
    val buyRounded: Double get() = when {
        portion != null -> Math.ceil(buy / portion.second - 1e-9)
        unit in WEIGHT_UNITS -> Math.ceil(buy / 50.0) * 50.0
        else -> Math.ceil(buy)
    }
    /** The unit [buyRounded] is in. */
    val buyUnit: String get() = portion?.first ?: unit
    fun inBuyUnit(amount: Double) = portion?.let { amount / it.second } ?: amount
}

/**
 * The shopping list for the [days] starting [from] (next Monday to Sunday): every food in the plan's
 * templates for those days, totalled in its own unit, minus what's in stock for its family. Batch slots
 * aren't counted (their ingredients depend on what you cook). Spices and other status-only items count
 * as enough unless marked out.
 */
fun Library.weekShopping(plan: PlanState, levels: List<StockLevel>, from: LocalDate, days: Int = 7): List<WeekItem> {
    val need = LinkedHashMap<String, Double>()
    val units = HashMap<String, MutableSet<String>>()
    repeat(days) { i ->
        val day = from.plusDays(i.toLong()).dayOfWeek.value
        app.shoku.data.db.Meals.ALL.forEach { meal ->
            plan.templateFor(day, meal)?.let { t ->
                plan.itemsOf(t.id).forEach { item ->
                    if (item.refType == RefType.FOOD && item.refId != null) inFoodUnit(item.refId, item.unit, item.qty)?.let { a ->
                        need[item.refId] = (need[item.refId] ?: 0.0) + a
                        units.getOrPut(item.refId) { HashSet() } += item.unit
                    }
                }
            }
        }
    }
    return need.mapNotNull { (foodId, amount) ->
        val f = foods[foodId] ?: return@mapNotNull null
        val family = levels.filter { l -> l.item.foodId != null && familyKey(l.item.foodId) == familyKey(foodId) && l.item.supplementId == null }
        if (family.any { !it.item.tracked && it.item.status != "out" }) return@mapNotNull null
        val have = family.filter { it.item.tracked }.sumOf { l ->
            val q = l.qty ?: return@sumOf 0.0
            when {
                l.item.unit == f.unit -> q
                foods[l.item.foodId]?.unit == f.unit -> inFoodUnit(l.item.foodId!!, l.item.unit, q) ?: 0.0
                else -> 0.0
            }
        }
        // A single household portion used throughout (not g/ml) makes a friendlier shopping unit.
        val portion = units[foodId]?.singleOrNull()?.takeIf { it != f.unit && it !in WEIGHT_UNITS }
            ?.let { label -> portionsOf(f).firstOrNull { it.label == label }?.let { label to it.qty } }
        WeekItem(foodId, f.name, f.unit, amount, have, portion).takeIf { it.buy > 0.0 }
    }.sortedBy { it.name.lowercase() }
}
