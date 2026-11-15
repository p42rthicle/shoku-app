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
import androidx.compose.ui.unit.dp
import app.shoku.data.SupplementState
import app.shoku.data.db.Slots
import app.shoku.data.db.Supplement
import app.shoku.domain.ScheduleRule
import app.shoku.domain.encodeSchedule
import app.shoku.domain.isDue
import app.shoku.domain.asNeeded
import app.shoku.domain.lastsUntil
import app.shoku.domain.parseSchedule
import app.shoku.domain.scheduleText
import app.shoku.domain.supplementStock
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.Segmented
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SeeMoreChip
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.asField
import app.shoku.ui.components.toAmount
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.LocalDate

/** Stock left and the day it runs out, e.g. "112 left · lasts until 6 Mar". */
fun stockLine(s: Supplement, st: SupplementState, today: LocalDate = LocalDate.now()): String {
    val left = supplementStock(st.stockItem(s), st.doses, st.moves) ?: return "Stock not counted"
    val until = s.lastsUntil(left, today)
    val unit = if (left == 1.0 || s.unit == "g") s.unit else s.unit + "s"
    val end = s.endDate?.let(LocalDate::parse)
    return "${formatQty(left)} $unit left" + when {
        !s.active -> ""
        // The schedule ends before the stock does: enough for the whole course.
        end != null && (until == null || until >= end) -> " · enough until the course ends (${end.dayMonth()})"
        until == null -> " · enough for over 2 years"
        until.isBefore(today) -> " · run out"
        else -> " · lasts until ${until.dayMonth()}"
    }
}

/** Supplement cards: when, how much, stock; tap to edit. */
fun androidx.compose.foundation.lazy.LazyListScope.supplementsSection(
    st: SupplementState,
    onToggleToday: (Supplement, Boolean) -> Unit,
    onEdit: (Supplement) -> Unit,
    open: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    onNew: () -> Unit = {},
) {
    val today = LocalDate.now()
    val due = st.supplements.filter { it.active && !it.asNeeded && it.isDue(today) }
    val all = "supps" in open
    val visible = when {
        all -> st.supplements
        due.isNotEmpty() -> due
        else -> st.supplements.take(3)
    }
    if (!all && due.isNotEmpty()) item(key = "supp-due-label") { SectionLabel("Due today", trailing = "${due.size} of ${st.supplements.size}") }
    visible.forEach { s ->
        item(key = "supp-" + s.id) {
            OutlinedCard(Modifier.fillMaxWidth(), onClick = { onEdit(s) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, color = if (s.active) ShokuColors.Ink else ShokuColors.Muted)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    }
                    if (s.asNeeded && s.active) {
                        val taken = st.taken(s, today)
                        Box(Modifier.clip(CircleShape).clickable { onToggleToday(s, taken) }) {
                            StatusChip(if (taken) "✓ Took one today" else "Took one", if (taken) ChipStyle.Filled else ChipStyle.Outlined)
                        }
                    } else if (s.isDue(today)) {
                        val taken = st.taken(s, today)
                        Box(Modifier.clip(CircleShape).clickable { onToggleToday(s, taken) }) {
                            StatusChip(if (taken) "✓ Taken" else "Due today", if (taken) ChipStyle.Filled else ChipStyle.Outlined)
                        }
                    } else if (!s.active) StatusChip("Off", ChipStyle.Tinted)
                }
                Spacer(Modifier.height(8.dp))
                Text(if (s.asNeeded) "${formatUnitQty(s.dose, s.unit)} · as needed" else "${formatUnitQty(s.dose, s.unit)} with ${s.slot.lowercase()} · ${s.scheduleText()}", style = MaterialTheme.typography.bodyMedium)
                Text(stockLine(s, st, today), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }
    }
    if (visible.size < st.supplements.size || all) item(key = "supp-more") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SeeMoreChip(st.supplements.size, visible.size.takeIf { !all } ?: (st.supplements.size - 1).coerceAtLeast(0), all, { onToggle("supps") }, what = "supplements")
        }
    }
    item(key = "supp-new") { app.shoku.ui.components.SecondaryButton("New supplement", onClick = onNew) }
    item(key = "supp-note") {
        Text(
            "Not medical advice: check doses and new supplements with a doctor.",
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
        )
    }
}

private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

/** Edit dose, slot, schedule, stock count and on/off. Multi-step schedules (D3) are kept unless changed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementEditor(s: Supplement, st: SupplementState, onSave: (Supplement, Double?) -> Unit, onDismiss: () -> Unit, isNew: Boolean = false, onDelete: ((Supplement) -> Unit)? = null) {
    var name by remember { mutableStateOf(s.name) }
    var detail by remember { mutableStateOf(s.detail) }
    var unit by remember { mutableStateOf(s.unit) }
    val rules = parseSchedule(s.schedule)
    val single = rules.singleOrNull()?.takeIf { it.until == null }
    var dose by remember { mutableStateOf(s.dose.asField()) }
    var slot by remember { mutableStateOf(s.slot) }
    var kind by remember { mutableStateOf(if (isNew) ScheduleRule.DAY else single?.every ?: if (rules.isEmpty()) "none" else "keep") }
    var day by remember { mutableStateOf(single?.day ?: "SUN") }
    var active by remember { mutableStateOf(s.active) }
    val current = supplementStock(st.stockItem(s), st.doses, st.moves)
    var count by remember { mutableStateOf(current?.let { formatQty(it) } ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (isNew) "New supplement" else s.name.ifBlank { "Supplement" }, style = MaterialTheme.typography.headlineSmall)
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Vitamin B12")
            LabeledField("Details (optional)", detail, { detail = it }, Modifier.fillMaxWidth(), placeholder = "e.g. 1500 mcg, brand")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Dose", dose, { dose = it }, Modifier.weight(1f), numeric = true, suffix = unit)
                LabeledField("One is a", unit, { unit = it.trim() }, Modifier.weight(1f), placeholder = "tablet, capsule, g")
            }
            LabeledField("Have now", count, { count = it }, Modifier.fillMaxWidth(), numeric = true, suffix = unit)
            Text("Take with", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
            Segmented(Slots.ALL.dropLast(1), Slots.ALL.indexOf(slot).coerceIn(0, 3)) { slot = Slots.ALL[it] }
            Text("How often", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
            val kinds = buildList { if (single == null && rules.isNotEmpty()) add("keep" to "As set"); add("none" to "As needed"); add(ScheduleRule.DAY to "Daily"); add(ScheduleRule.WEEK to "Weekly"); add(ScheduleRule.MONTH to "Monthly") }
            Segmented(kinds.map { it.second }, kinds.indexOfFirst { it.first == kind }.coerceAtLeast(0)) { kind = kinds[it].first }
            if (kind == "keep") Text(s.scheduleText(), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            if (kind == ScheduleRule.WEEK || kind == ScheduleRule.MONTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAYS.forEach { d ->
                        Box(Modifier.clip(CircleShape).clickable { day = d }) { StatusChip(d.take(2).lowercase().replaceFirstChar { it.uppercase() }, if (day == d) ChipStyle.Filled else ChipStyle.Tinted) }
                    }
                }
                if (kind == ScheduleRule.MONTH) Text("The first of that weekday in each month", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            SwitchRow("Taking it", if (active) "Shows on Today and in reminders" else "Off: hidden from Today and reminders", active, { active = it })
            if (s.howTo.isNotBlank()) Text(s.howTo, style = MaterialTheme.typography.bodySmall)
            if (s.cautions.isNotBlank()) Text(s.cautions, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            s.endDate?.let { Text("Course ends ${LocalDate.parse(it).dayMonth()} ${LocalDate.parse(it).year}", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (name.isBlank()) "Name it first" else if (isNew) "Add supplement" else "Save", onClick = {
                if (name.isBlank()) return@PrimaryButton
                val schedule = when (kind) {
                    "keep" -> s.schedule
                    "none" -> "[]"
                    ScheduleRule.DAY -> encodeSchedule(listOf(ScheduleRule(ScheduleRule.DAY)))
                    else -> encodeSchedule(listOf(ScheduleRule(kind, day)))
                }
                val newCount = count.toAmount()?.takeIf { it >= 0 && it != current }
                onSave(s.copy(name = name.trim(), detail = detail.trim(), unit = unit.ifBlank { s.unit }, dose = dose.toAmount()?.takeIf { it > 0 } ?: s.dose, slot = slot, schedule = schedule, active = active), newCount)
            })
            if (!isNew && onDelete != null) {
                var confirm by remember { mutableStateOf(false) }
                Text(if (confirm) "Tap again to remove it (its history stays in Insights)" else "Remove this supplement", style = MaterialTheme.typography.labelLarge,
                    color = if (confirm) ShokuColors.Over else ShokuColors.Muted,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { if (confirm) onDelete(s) else confirm = true }.padding(vertical = 12.dp))
            }
        }
    }
}
