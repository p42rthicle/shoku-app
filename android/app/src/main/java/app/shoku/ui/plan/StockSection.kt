package app.shoku.ui.plan

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockPlaces
import app.shoku.domain.ShoppingItem
import app.shoku.domain.StockLevel
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.shown
import app.shoku.ui.components.SeeMoreChip
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.toAmount
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.LocalDate

/** Stock screen state: levels per item and the shopping list. */
data class StockView(
    val levels: List<StockLevel> = emptyList(),
    val shopping: List<ShoppingItem> = emptyList(),
    val hiddenCount: Int = 0,
    /** Next Monday to Sunday: what the plan needs that isn't at home. */
    val week: List<app.shoku.domain.WeekItem> = emptyList(),
    val weekFrom: java.time.LocalDate? = null,
    /** Per stock food: the units it can be counted in, each with its size in the food's own unit (own unit first). */
    val units: Map<String, List<Pair<String, Double>>> = emptyMap(),
    /** Per food: "350 g in stock · Pantry", for its Edit food sheet. */
    val texts: Map<String, String> = emptyMap(),
    /** Per stock food: its brand, shown as a chip on the row like in the food lists. */
    val brands: Map<String, String> = emptyMap(),
)

/** The latest [StockView.texts], kept current by ShokuApp, so any Edit food sheet can show what's at home. */
object StockText { val map = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap()) }

/**
 * Only what matches [query], by name or by brand: "SDS" or "Haldi Ram" (= Haldiram's; spaces and
 * apostrophes don't matter for brands) finds everything of that brand.
 */
fun StockView.matching(query: String): StockView {
    if (query.isBlank()) return this
    fun hit(name: String) = app.shoku.domain.matchesAll(app.shoku.domain.searchKey(name), query)
    val compact = app.shoku.domain.searchKey(query).filter { it.isLetterOrDigit() }
    fun brandHit(foodId: String?): Boolean {
        val b = foodId?.let { brands[it] } ?: return false
        return hit(b) || (compact.length >= 3 && compact in app.shoku.domain.searchKey(b).filter { it.isLetterOrDigit() })
    }
    return copy(
        levels = levels.filter { hit(it.item.name) || brandHit(it.item.foodId) },
        shopping = shopping.filter { hit(it.name) || brandHit(it.foodId) },
        week = week.filter { hit(it.name) || brandHit(it.foodId) },
    )
}

/**
 * What's at home for global search: by name or brand; a place ("pantry", "fridge", "spices") lists what's
 * there; "stock" or "home" lists everything. Supplements have their own section.
 */
fun StockView.homeMatches(query: String): List<StockLevel> {
    val q = app.shoku.domain.searchKey(query).trim()
    if (q.isEmpty()) return emptyList()
    val food = levels.filter { it.item.supplementId == null }
    val word = q.removeSuffix("s")
    val place = app.shoku.data.db.StockPlaces.ALL.filter { it != app.shoku.data.db.StockPlaces.SUPPLEMENTS }
        .firstOrNull { app.shoku.domain.searchKey(it).removeSuffix("s") == word }
    val order = app.shoku.data.db.StockPlaces.ALL
    return when {
        q in setOf("stock", "home", "at home", "inventory", "vorrat") -> food
        place != null -> food.filter { it.item.place == place }
        else -> matching(query).levels.filter { it.item.supplementId == null }
    }.sortedWith(compareBy<StockLevel>({ order.indexOf(it.item.place) }, { it.item.name.lowercase() }))
}

/** A stock row's text, as on the Stock tab: "≈ 60 g left · about 2 pack · running low". */
fun StockView.rowText(l: StockLevel): String = levelText(l, friendly(this, l.item))

/** A household unit to show next to grams ("about 6 pc"), if the food has one. */
private fun friendly(view: StockView, i: StockItem): Pair<String, Double>? =
    i.foodId?.let { view.units[it] }?.drop(1)?.takeIf { i.unit in app.shoku.domain.WEIGHT_UNITS }
        ?.let { us -> us.firstOrNull { it.first in listOf("pc", "medium", "slice", "piece", "bar", "serving", "pack") } ?: us.firstOrNull() }

private fun levelText(l: StockLevel, hint: Pair<String, Double>? = null): String {
    val i = l.item
    if (!i.tracked) return when (i.status) { "low" -> "Running low"; "out" -> "Out"; else -> "Have it" }
    val qty = l.qty ?: return "Amount not set · tap to add"
    if (qty <= 0.0) return "Used up"
    val days = l.daysLeft?.let { if (it > 60) " · lasts months" else " · ~${Math.round(it)} days" } ?: ""
    val about = hint?.takeIf { it.second > 0 }?.let { (label, size) -> " · about ${formatUnitQty(Math.round(qty / size * 10) / 10.0, label)}" } ?: ""
    val low = if (i.lowAt != null && qty <= i.lowAt) " · running low" else ""
    return "≈ ${formatUnitQty(Math.round(qty * 10) / 10.0, i.unit)} left$about$days$low"
}

private fun bestBeforeText(i: StockItem, today: LocalDate): Pair<String, Boolean>? {
    val d = i.bestBefore?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val left = java.time.temporal.ChronoUnit.DAYS.between(today, d)
    return when {
        left < 0 -> "Past best-before (${d.dayMonth()})" to true
        left <= 2 -> "Use by ${d.dayMonth()}" to true
        else -> "Best before ${d.dayMonth()}" to false
    }
}

fun LazyListScope.stockSection(
    view: StockView,
    busy: String?,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onSay: () -> Unit,
    /** Add a bill (screenshots or a photo); and the card about bills (working, or new items to check). */
    onBill: () -> Unit = {},
    billCard: (@Composable () -> Unit)? = null,
    onBought: (ShoppingItem) -> Unit,
    onOpen: (StockItem) -> Unit,
    onDismiss: (ShoppingItem) -> Unit,
    onShowHidden: () -> Unit,
    onBoughtWeek: (app.shoku.domain.WeekItem) -> Unit = {},
    /** Swiped away from For next week: hidden until its stock changes. */
    onDismissWeek: (app.shoku.domain.WeekItem) -> Unit = {},
    open: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    /** Search: only what matches, and every list fully open. */
    query: String = "",
) {
    val today = LocalDate.now()
    val searching = query.isNotBlank()
    val open = if (searching) open + listOf("shop", "week") + StockPlaces.ALL.map { "place-$it" } else open
    val view = view.matching(query)
    if (billCard != null) item(key = "bill-card") { billCard() }
    item(key = "stock-add") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Scan", onClick = onScan, modifier = Modifier.weight(1f))
            SecondaryButton("Find & add", onClick = onSearch, modifier = Modifier.weight(1f))
            SecondaryButton("Say it", onClick = onSay, modifier = Modifier.weight(1f))
            SecondaryButton("Bill", onClick = onBill, modifier = Modifier.weight(1f))
        }
        busy?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
    }
    // What's at home comes first; an empty start says how to begin.
    val foodLevels = view.levels.filter { it.item.place != StockPlaces.SUPPLEMENTS }
    item(key = "home-label") { SectionLabel(if (searching) "At home · matches" else "At home", trailing = if (foodLevels.isEmpty()) null else "${foodLevels.size}") }
    if (foodLevels.isEmpty() && searching) {
        item(key = "stock-nomatch") {
            Text(
                "Nothing at home matches “${query.trim()}”." + if (view.shopping.isNotEmpty() || view.week.isNotEmpty()) " It's on your shopping list below." else "",
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
        }
    } else if (foodLevels.isEmpty()) {
        item(key = "stock-empty") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text("Your stock is empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add what's at home to begin: scan packs, search, or say it (\"2 kg chana dal, 30 eggs, 1 l milk\"). Eating and cooking then take it off by themselves, and To buy fills in from your plan.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
        }
    }
    StockPlaces.ALL.filter { it != StockPlaces.SUPPLEMENTS }.forEach { place ->
        val rows = view.levels.filter { it.item.place == place }.sortedBy { it.item.name.lowercase() }
        if (rows.isEmpty()) return@forEach
        item(key = "place-$place") { SectionLabel(place, trailing = "${rows.size}") }
        item(key = "rows-$place") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                rows.shown("place-$place" in open, 4).forEachIndexed { idx, l ->
                    if (idx > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickable { onOpen(l.item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            app.shoku.ui.components.NameWithTag(l.item.name, l.item.foodId?.let { view.brands[it] }, MaterialTheme.typography.titleMedium)
                            Text(levelText(l, friendly(view, l.item)), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                            bestBeforeText(l.item, today)?.let { (t, warn) -> Text(t, style = MaterialTheme.typography.bodySmall, color = if (warn) ShokuColors.Over else ShokuColors.Muted) }
                        }
                    }
                }
            }
        }
        item(key = "more-$place") { SeeMoreChip(rows.size, 4, "place-$place" in open, { onToggle("place-$place") }) }
    }
    if (view.shopping.isNotEmpty() || view.hiddenCount > 0) {
        item(key = "shop-label") { SectionLabel("To buy · ${view.shopping.size}", trailing = if (view.shopping.isEmpty()) null else "swipe to remove") }
        if (view.shopping.isNotEmpty()) item(key = "shop") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                view.shopping.shown("shop" in open, 3).forEachIndexed { idx, s ->
                    androidx.compose.runtime.key(s.key) {
                        if (idx > 0) Hairline()
                        app.shoku.ui.today.SwipeToDelete(enabled = true, onDelete = { onDismiss(s) }) {
                            Row(Modifier.fillMaxWidth().background(ShokuColors.Paper).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                                }
                                Box(Modifier.clip(CircleShape).clickable { onBought(s) }) { StatusChip("Bought", ChipStyle.Filled) }
                            }
                        }
                    }
                }
            }
        }
        item(key = "shop-more") { SeeMoreChip(view.shopping.size, 3, "shop" in open, { onToggle("shop") }) }
        if (view.hiddenCount > 0) item(key = "shop-hidden") {
            Text(
                "Show ${view.hiddenCount} hidden", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                modifier = Modifier.clip(ShokuShapes.Button).clickable(onClick = onShowHidden).padding(vertical = 6.dp),
            )
        }
    }
    if (view.week.isNotEmpty()) {
        item(key = "week-label") {
            SectionLabel("For next week · ${view.week.size}", trailing = view.weekFrom?.let { "from ${it.dayMonth()} · swipe to remove" })
        }
        item(key = "week") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                view.week.shown("week" in open, 3).forEachIndexed { idx, w ->
                    androidx.compose.runtime.key(w.foodId) {
                    if (idx > 0) Hairline()
                    app.shoku.ui.today.SwipeToDelete(enabled = true, onDelete = { onDismissWeek(w) }) {
                    Row(Modifier.fillMaxWidth().background(ShokuColors.Paper).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${w.name} · ${formatUnitQty(w.buyRounded, w.buyUnit)}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Plan needs ${formatUnitQty(Math.round(w.inBuyUnit(w.need) * 10) / 10.0, w.buyUnit)}, have ${formatUnitQty(Math.round(w.inBuyUnit(w.have) * 10) / 10.0, w.buyUnit)}",
                                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        }
                        Box(Modifier.clip(CircleShape).clickable { onBoughtWeek(w) }) { StatusChip("Bought", ChipStyle.Filled) }
                    }
                    }
                    }
                }
            }
        }
    }
    if (view.week.isNotEmpty()) item(key = "week-more") { SeeMoreChip(view.week.size, 3, "week" in open, { onToggle("week") }) }
    listOf(StockPlaces.SUPPLEMENTS).forEach { place ->
        val rows = view.levels.filter { it.item.place == place }.sortedBy { it.item.name.lowercase() }
        if (rows.isEmpty()) return@forEach
        item(key = "place-$place") { SectionLabel(place, trailing = "${rows.size}") }
        item(key = "rows-$place") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                rows.shown("place-$place" in open, 4).forEachIndexed { idx, l ->
                    if (idx > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickable { onOpen(l.item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            app.shoku.ui.components.NameWithTag(l.item.name, l.item.foodId?.let { view.brands[it] }, MaterialTheme.typography.titleMedium)
                            Text(levelText(l, friendly(view, l.item)), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                            bestBeforeText(l.item, today)?.let { (t, warn) -> Text(t, style = MaterialTheme.typography.bodySmall, color = if (warn) ShokuColors.Over else ShokuColors.Muted) }
                        }
                    }
                }
            }
        }
        item(key = "more-$place") { SeeMoreChip(rows.size, 4, "place-$place" in open, { onToggle("place-$place") }) }
    }
}

/** Edit one stock item: recount, where it is, low mark, best-before, tracked or status-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockItemEditor(
    level: StockLevel,
    onSave: (StockItem, Double?) -> Unit,
    onDelete: (StockItem) -> Unit,
    onDismiss: () -> Unit,
    /** Units to count in, each with its size in the item's unit (own unit first). */
    units: List<Pair<String, Double>> = emptyList(),
    /** For a food (creatine added as one): make it a supplement instead. */
    onMoveToSupplements: (() -> Unit)? = null,
    /** Opens the food itself (its editor: name, values, units). */
    onEditFood: (() -> Unit)? = null,
    /** A new unit for the food ("pc" = 150 g), to count in straight away. */
    onAddUnit: ((String, Double) -> Unit)? = null,
) {
    val i = level.item
    val choices = units.ifEmpty { listOf(i.unit to 1.0) }
    var unitIdx by remember { mutableStateOf(0) }
    var addingUnit by remember { mutableStateOf(false) }
    var newUnit by remember { mutableStateOf("pc") }
    var newUnitSize by remember { mutableStateOf("") }
    // A unit just added: switch to it once it's in the list (the amount converts).
    var selectNext by remember { mutableStateOf<String?>(null) }
    val factor = choices[unitIdx].second
    var count by remember { mutableStateOf(level.qty?.let { formatQty(Math.round(it * 10) / 10.0) } ?: "") }
    var place by remember { mutableStateOf(i.place) }
    var low by remember { mutableStateOf(i.lowAt?.let { formatQty(it) } ?: "") }
    var bestBefore by remember { mutableStateOf(i.bestBefore ?: "") }
    var tracked by remember { mutableStateOf(i.tracked) }
    var status by remember { mutableStateOf(i.status) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The title opens the food (a small edit icon says so).
            Row(
                Modifier.fillMaxWidth().clip(ShokuShapes.Button).then(if (onEditFood != null) Modifier.clickable(onClick = onEditFood) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(i.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
                if (onEditFood != null) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Edit, "Edit food", tint = ShokuColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
            level.perDay?.let { Text("Uses about ${formatUnitQty(Math.round(it * 10) / 10.0, i.unit)} a day", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
            SwitchRow("Track the amount", if (tracked) "Goes down as you eat and cook; below \"Buy below\" it's running low and goes on To buy" else "No amount, just have / low / out", tracked, { tracked = it })
            if (!tracked && i.supplementId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("have" to "Have", "low" to "Low", "out" to "Out").forEach { (k, label) ->
                        Box(Modifier.clip(CircleShape).clickable { status = k }) { StatusChip(label, if (status == k) ChipStyle.Filled else ChipStyle.Tinted) }
                    }
                }
            }
            if (tracked && i.supplementId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("There's now", count, { count = it }, Modifier.weight(1f), numeric = true, suffix = choices[unitIdx].first)
                    LabeledField("Buy below", low, { low = it }, Modifier.weight(1f), numeric = true, suffix = choices[unitIdx].first)
                }
                fun switchTo(k: Int) {
                    if (k == unitIdx) return
                    val from = choices[unitIdx].second
                    val size = choices[k].second
                    fun conv(t: String) = t.toAmount()?.let { formatQty(Math.round(it * from / size * 10) / 10.0) } ?: t
                    count = conv(count); low = conv(low)
                    unitIdx = k
                }
                androidx.compose.runtime.LaunchedEffect(choices, selectNext) {
                    val want = selectNext ?: return@LaunchedEffect
                    val k = choices.indexOfFirst { it.first == want }
                    if (k >= 0) { switchTo(k); selectNext = null }
                }
                if (choices.size > 1 || onAddUnit != null) {
                    // Show the amount in another unit: 1520 g = 38 pc. The numbers convert; what's saved is the same.
                    Text("Counted in", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEachIndexed { k, (label, _) ->
                            Box(Modifier.clip(CircleShape).clickable { switchTo(k) }) { StatusChip(label, if (k == unitIdx) ChipStyle.Filled else ChipStyle.Tinted) }
                        }
                        if (onAddUnit != null) Box(Modifier.clip(CircleShape).clickable { addingUnit = !addingUnit }) { StatusChip("+ unit", ChipStyle.Tinted) }
                    }
                    if (addingUnit && onAddUnit != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabeledField("Unit", newUnit, { newUnit = it }, Modifier.weight(1f), placeholder = "pc, pack")
                        LabeledField("One is", newUnitSize, { newUnitSize = it }, Modifier.weight(1f), numeric = true, suffix = i.unit)
                        Text("Add", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink, modifier = Modifier.clip(ShokuShapes.Button).clickable {
                            val label = newUnit.trim()
                            val size = newUnitSize.toAmount()
                            if (label.isNotEmpty() && size != null && size > 0) {
                                onAddUnit(label, size); selectNext = label; addingUnit = false; newUnitSize = ""
                            }
                        }.padding(10.dp))
                    }
                    if (factor != 1.0) count.toAmount()?.let { n ->
                        Text("= ${formatUnitQty(Math.round(n * factor * 10) / 10.0, i.unit)}", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    }
                }
            }
            // Best before: a date picker (a typo in a text field used to wipe the saved date).
            var pickingDate by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(ShokuShapes.Button).background(ShokuColors.Tint).clickable { pickingDate = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Best before", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    Text(bestBefore.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).dayMonth() + " " + LocalDate.parse(it).year }.getOrNull() } ?: "Not set · tap to pick",
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (bestBefore.isNotBlank()) Text("Clear", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                    modifier = Modifier.clip(ShokuShapes.Button).clickable { bestBefore = "" }.padding(8.dp))
            }
            if (pickingDate) {
                val initial = runCatching { LocalDate.parse(bestBefore) }.getOrNull() ?: LocalDate.now().plusDays(7)
                val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                androidx.compose.material3.DatePickerDialog(
                    onDismissRequest = { pickingDate = false },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            state.selectedDateMillis?.let { bestBefore = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
                            pickingDate = false
                        }) { Text("Set", color = ShokuColors.Ink) }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { pickingDate = false }) { Text("Cancel", color = ShokuColors.Muted) } },
                ) { androidx.compose.material3.DatePicker(state = state) }
            }
            Text("Where", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StockPlaces.ALL.dropLast(1).forEach { p ->
                    Box(Modifier.clip(CircleShape).clickable { place = p }) { StatusChip(p, if (place == p) ChipStyle.Filled else ChipStyle.Tinted) }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (onEditFood != null) app.shoku.ui.components.SecondaryButton("Edit the food: name, values, units", onClick = onEditFood)
            if (onMoveToSupplements != null) app.shoku.ui.components.SecondaryButton("It's a supplement: move to Supplements", onClick = onMoveToSupplements)
            PrimaryButton("Save", onClick = {
                val bb = bestBefore.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
                val newCount = count.toAmount()?.times(factor)?.takeIf { tracked && it >= 0 && (level.qty == null || Math.abs(it - level.qty) > 0.05 * factor + 0.05) } // shown in pc, 38 × 39.9 isn't a recount
                // A spice counted for the first time runs low at a fifth of it, unless a mark is set.
                val lowAt = low.toAmount()?.times(factor) ?: if (i.countQty == null && newCount != null) app.shoku.data.spiceLow(place, newCount) else null
                onSave(i.copy(place = place, lowAt = lowAt, bestBefore = bb, tracked = tracked, status = status, countQty = i.countQty ?: newCount), newCount)
            })
            var confirm by remember { mutableStateOf(false) }
            Text(if (confirm) "Tap again to remove it" else "Remove from stock", style = MaterialTheme.typography.labelLarge,
                color = if (confirm) ShokuColors.Over else ShokuColors.Muted,
                modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { if (confirm) onDelete(i) else confirm = true }.padding(vertical = 12.dp))
        }
    }
}
