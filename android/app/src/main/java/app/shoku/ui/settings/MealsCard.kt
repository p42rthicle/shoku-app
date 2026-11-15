package app.shoku.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.shoku.data.MealSettingsStore
import app.shoku.data.db.Meals
import app.shoku.domain.HiddenRange
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.StatusChip
import app.shoku.ui.dayMonth
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun clockText(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

private fun LocalDate.label(today: LocalDate = LocalDate.now()) =
    if (this == today) "today" else dayMonth() + if (year != today.year) " $year" else ""

private fun rangeText(r: HiddenRange): String {
    val from = LocalDate.parse(r.from)
    val to = r.to?.let(LocalDate::parse)
    return if (to == null) "Hidden from ${from.label()}" else "Hidden ${from.label()} – ${to.label()}"
}

/**
 * Meal start times (they decide which meal + opens) and which meals are in use. Turning a meal off
 * hides it from a date, optionally until a date; days that already have food in it keep showing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsCard() {
    val settings by MealSettingsStore.current.collectAsState()
    val today = LocalDate.now()
    var editingTime by remember { mutableStateOf<String?>(null) }
    var hiding by remember { mutableStateOf<String?>(null) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Text("Meals", style = MaterialTheme.typography.titleLarge)
        Text(
            "Start times decide which meal + opens. A meal you turn off disappears from Today and Add; days where you logged something in it still show it.",
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
        )
        Meals.ALL.forEach { meal ->
            Hairline()
            val on = !settings.hiddenOn(meal, today)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meal, style = MaterialTheme.typography.titleMedium, color = if (on) ShokuColors.Ink else ShokuColors.Muted)
                    Text(
                        "Starts ${clockText(settings.startOf(meal))} · change",
                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                        modifier = Modifier.clip(ShokuShapes.Button).clickable { editingTime = meal },
                    )
                    settings.hidden.filter { it.meal == meal && (it.to == null || it.to >= today.toString()) }.forEach { r ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rangeText(r), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.clip(CircleShape).clickable { MealSettingsStore.update { s -> s.copy(hidden = s.hidden - r) } }) {
                                StatusChip("Remove", ChipStyle.Tinted)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = on,
                    onCheckedChange = { want -> if (want) MealSettingsStore.update { it.showFrom(meal, today) } else hiding = meal },
                    colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "To hide a meal for dates ahead (a trip, a fasting week), turn it off and pick the dates.",
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
        )
    }

    editingTime?.let { meal ->
        val start = settings.startOf(meal)
        val state = rememberTimePickerState(start / 60, start % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editingTime = null },
            containerColor = ShokuColors.Paper,
            title = { Text("$meal starts at") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    MealSettingsStore.update { it.copy(starts = it.starts + (meal to state.hour * 60 + state.minute)) }
                    editingTime = null
                }) { Text("Save", color = ShokuColors.Ink) }
            },
            dismissButton = { TextButton(onClick = { editingTime = null }) { Text("Cancel", color = ShokuColors.Muted) } },
        )
    }

    hiding?.let { meal -> HideDialog(meal, onDone = { from, to -> MealSettingsStore.update { it.hide(meal, from, to) }; hiding = null }, onCancel = { hiding = null }) }
}

/** Hide a meal: from today (or a chosen day), with no end or until a chosen day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideDialog(meal: String, onDone: (LocalDate, LocalDate?) -> Unit, onCancel: () -> Unit) {
    var from by remember { mutableStateOf(LocalDate.now()) }
    var to by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "from" or "to"

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = ShokuColors.Paper,
        title = { Text("Hide ${meal.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("From", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                Box(Modifier.clip(CircleShape).clickable { picking = "from" }) { StatusChip(from.label().replaceFirstChar { it.uppercase() }, ChipStyle.Filled) }
                Text("Until", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clip(CircleShape).clickable { to = null }) { StatusChip("No end", if (to == null) ChipStyle.Filled else ChipStyle.Tinted) }
                    Box(Modifier.clip(CircleShape).clickable { picking = "to" }) {
                        StatusChip(to?.label()?.replaceFirstChar { it.uppercase() } ?: "Pick a date", if (to != null) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
                Text("Days where you logged ${meal.lowercase()} still show it.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(from, to?.takeIf { !it.isBefore(from) }) }) { Text("Hide", color = ShokuColors.Ink) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = ShokuColors.Muted) } },
    )

    picking?.let { which ->
        val initial = (if (which == "from") from else to ?: from.plusDays(7)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "from") { from = d; if (to != null && to!!.isBefore(d)) to = d } else to = d
                    }
                    picking = null
                }) { Text("OK", color = ShokuColors.Ink) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel", color = ShokuColors.Muted) } },
        ) { DatePicker(state) }
    }
}
