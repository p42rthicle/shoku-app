package app.shoku.ui.plan

import androidx.compose.foundation.clickable
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
import app.shoku.data.db.Meals
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Library
import app.shoku.domain.PlanState
import app.shoku.domain.SLOT
import app.shoku.domain.templateTotals
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.QuantitySheet
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.Segmented
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.shown
import app.shoku.ui.components.SeeMoreChip
import app.shoku.ui.components.StatusChip
import app.shoku.ui.formatUnitQty
import app.shoku.ui.library.ItemPicker
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.util.UUID

private val DAY_SHORT = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/** "Mon, Wed–Sun" from weekday numbers (1 = Monday). */
fun daysText(days: List<Int>): String {
    if (days.size == 7) return "Every day"
    if (days.isEmpty()) return "Not in the week"
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val runs = mutableListOf<IntRange>()
    days.sorted().forEach { d -> if (runs.isNotEmpty() && runs.last().last == d - 1) runs[runs.lastIndex] = runs.last().first..d else runs += d..d }
    return runs.joinToString(", ") { r -> if (r.first == r.last) names[r.first - 1] else if (r.last == r.first + 1) "${names[r.first - 1]}, ${names[r.last - 1]}" else "${names[r.first - 1]}–${names[r.last - 1]}" }
}

private fun itemName(library: Library, i: TemplateItem) = if (i.refType == SLOT) i.label.ifBlank { "Batch in the fridge" } else i.refId?.let { library.nameOf(i.refType, it) } ?: "?"

/** Week totals and one card per template, grouped by meal. */
fun LazyListScope.mealsSection(
    plan: PlanState, library: Library?, goalKcal: Double?, goalProtein: Double?, onEdit: (MealTemplate?) -> Unit,
    open: Set<String> = emptySet(), onToggle: (String) -> Unit = {},
) {
    val lib = library ?: return
    item(key = "week") {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Text("Planned per day", style = MaterialTheme.typography.titleMedium)
            Text(
                "Without the sabzi or dal batch (about 100–200 kcal). Goal ${goalKcal?.let { Math.round(it) } ?: "–"} kcal · ${goalProtein?.let { Math.round(it) } ?: "–"} g protein.",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            // Today and tomorrow; the whole week behind "See all".
            val today = java.time.LocalDate.now().dayOfWeek.value
            val order = (0..6).map { (today - 1 + it) % 7 + 1 }
            order.shown("days" in open, 2).forEach { d ->
                val total = Meals.ALL.mapNotNull { m -> plan.templateFor(d, m) }.fold(app.shoku.domain.Nutrients.ZERO) { acc, t -> acc + lib.templateTotals(plan.itemsOf(t.id)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(if (d == today) "Today" else DAY_SHORT[d - 1], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Text("${Math.round(total.kcal ?: 0.0)} kcal", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${Math.round(total.protein ?: 0.0)} g protein", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            SeeMoreChip(7, 2, "days" in open, { onToggle("days") }, what = "days")
        }
    }
    Meals.ALL.forEach { meal ->
        val templates = plan.templates.filter { it.meal == meal && !it.deleted }
        if (templates.isEmpty()) return@forEach
        item(key = "label-$meal") { SectionLabel(meal, trailing = "${templates.size}") }
        templates.shown("tpl-$meal" in open, 2).forEach { t ->
            item(key = "tpl-" + t.id) {
                val items = plan.itemsOf(t.id)
                val n = lib.templateTotals(items)
                OutlinedCard(Modifier.fillMaxWidth(), onClick = { onEdit(t) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(daysText(plan.weekdaysOf(t.id)), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${Math.round(n.kcal ?: 0.0)} kcal", style = MaterialTheme.typography.labelLarge)
                            Text("${Math.round(n.protein ?: 0.0)} g protein", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        items.joinToString(" · ") { i -> itemName(lib, i) + if (i.refType == SLOT) "" else " ${formatUnitQty(i.qty, i.unit)}" },
                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (templates.size > 2) item(key = "more-$meal") { SeeMoreChip(templates.size, 2, "tpl-$meal" in open, { onToggle("tpl-$meal") }) }
    }
    item(key = "new-template") { SecondaryButton("New meal template", onClick = { onEdit(null) }) }
}

/** Create or edit a template: name, meal, weekdays and items. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditor(
    template: MealTemplate?,
    plan: PlanState,
    library: Library,
    onSave: (MealTemplate, List<TemplateItem>, Set<Int>) -> Unit,
    onDelete: (MealTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val id = remember { template?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(template?.name ?: "") }
    var meal by remember { mutableStateOf(template?.meal ?: Meals.BREAKFAST) }
    var days by remember { mutableStateOf(template?.let { plan.weekdaysOf(it.id).toSet() } ?: emptySet()) }
    var items by remember { mutableStateOf(template?.let { plan.itemsOf(it.id) } ?: emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<TemplateItem?>(null) }
    val n = library.templateTotals(items)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (template == null) "New meal template" else "Edit meal", style = MaterialTheme.typography.headlineSmall)
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Chicken, curd rice, vegetables")
            Segmented(Meals.ALL, Meals.ALL.indexOf(meal).coerceAtLeast(0)) { meal = Meals.ALL[it] }
            Text("Days", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    Box(Modifier.clip(CircleShape).clickable { days = if (d in days) days - d else days + d }) {
                        StatusChip(DAY_SHORT[d - 1], if (d in days) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
            }
            Text("${Math.round(n.kcal ?: 0.0)} kcal · ${Math.round(n.protein ?: 0.0)} g protein", style = MaterialTheme.typography.titleMedium)
            items.forEachIndexed { idx, i ->
                Hairline()
                Row(Modifier.fillMaxWidth().clickable(enabled = i.refType != SLOT) { amountFor = i }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(itemName(library, i), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val k = if (i.refType == SLOT || i.refId == null) null else library.entryNutrients(i.refType, i.refId, i.unit, i.qty)?.kcal
                        Text(
                            if (i.refType == SLOT) "1 serving of whatever batch is in the fridge" else "${formatUnitQty(i.qty, i.unit)}${k?.let { " · ${Math.round(it)} kcal" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                        )
                    }
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                        modifier = Modifier.clip(ShokuShapes.Button).clickable { items = items.filterIndexed { j, _ -> j != idx } }.padding(8.dp))
                }
            }
            Hairline()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Add item", onClick = { picking = true }, modifier = Modifier.weight(1f))
                if (items.none { it.refType == SLOT }) {
                    SecondaryButton("Add batch slot", onClick = {
                        items = items + TemplateItem(UUID.randomUUID().toString(), id, SLOT, null, "Sabzi or dal (batch in the fridge)", "serving", 1.0)
                    }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (name.isBlank()) "Name it first" else "Save", onClick = {
                if (name.isBlank()) return@PrimaryButton
                onSave(MealTemplate(id, name.trim(), meal, template?.position ?: plan.templates.size), items, days)
            })
            if (template != null) {
                var confirm by remember { mutableStateOf(false) }
                Text(if (confirm) "Tap again to delete it (its days become empty)" else "Delete this template", style = MaterialTheme.typography.labelLarge,
                    color = if (confirm) ShokuColors.Over else ShokuColors.Muted,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { if (confirm) onDelete(template) else confirm = true }.padding(vertical = 12.dp))
            }
        }
    }

    if (picking) {
        ItemPicker(library, "Add to meal", excludeDishId = null, onPick = { type, refId, lib ->
            val units = lib.unitsFor(type, refId)
            val unit = lib.defaultUnit(type, refId) ?: units.firstOrNull() ?: "g"
            items = items + TemplateItem(UUID.randomUUID().toString(), id, type, refId, "", unit, if (type == app.shoku.data.db.RefType.FOOD && lib.foods[refId]?.unit == unit) lib.foods[refId]!!.baseQty else 1.0)
            picking = false
            amountFor = items.last()
        }, onDismiss = { picking = false })
    }
    amountFor?.let { i ->
        val refId = i.refId ?: return@let
        QuantitySheet(
            title = itemName(library, i), subtitle = null,
            units = library.unitsFor(i.refType, refId).ifEmpty { listOf(i.unit) },
            initialUnit = i.unit, initialQty = i.qty, initialStatus = app.shoku.data.db.EntryStatus.PLANNED, showStatus = false,
            nutrientsFor = { u, q -> library.entryNutrients(i.refType, refId, u, q) },
            confirmLabel = "Set",
            onConfirm = { r -> items = items.map { if (it.id == i.id) it.copy(unit = r.unit, qty = r.qty) else it }; amountFor = null },
            onDismiss = { amountFor = null },
        )
    }
}
