package app.shoku.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.shoku.data.db.Meals
import app.shoku.data.notify.ReminderSettings
import app.shoku.data.notify.Reminders
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

private fun clock(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

/** Meal reminders (only when the meal is still empty) and the Sunday summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersCard() {
    val context = LocalContext.current
    val settings = remember { ReminderSettings(context) }
    var version by remember { mutableIntStateOf(0) } // re-read the settings after a change
    var editing by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }

    fun change(block: () -> Unit) {
        val apply: () -> Unit = { block(); Reminders.scheduleAll(context); version++ }
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(context)) {
            afterPermission = apply
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else apply()
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        @Suppress("UNUSED_EXPRESSION") version
        Text("Reminders", style = MaterialTheme.typography.titleLarge)
        Text("Only when that meal is still empty. \"Log usual\" on the notification logs it without opening the app.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        Meals.ALL.forEach { meal ->
            Hairline()
            val time = settings.timeFor(meal)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meal, style = MaterialTheme.typography.titleMedium)
                    Text(
                        time?.let { "${clock(it)} · change time" } ?: "Off",
                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                        modifier = if (time != null) Modifier.clip(ShokuShapes.Button).clickable { editing = meal } else Modifier,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = time != null,
                    onCheckedChange = { on -> change { settings.setTime(meal, if (on) ReminderSettings.DEFAULTS[meal] else null) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
                )
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sunday shopping list", style = MaterialTheme.typography.titleMedium)
                Text("Sundays at 10:00: what next week's plan needs that isn't at home", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            Switch(
                checked = settings.shopping,
                onCheckedChange = { on -> change { settings.shopping = on } },
                colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
            )
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prep", style = MaterialTheme.typography.titleMedium)
                Text("Sunday and Wednesday 18:00: chicken to marinate, paneer to make, batch to cook for the next days", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            Switch(
                checked = settings.prep,
                onCheckedChange = { on -> change { settings.prep = on } },
                colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
            )
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Supplements", style = MaterialTheme.typography.titleMedium)
                Text("With the meal reminder (or at its usual time if that's off), with a Taken button", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            Switch(
                checked = settings.supplements,
                onCheckedChange = { on -> change { settings.supplements = on } },
                colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
            )
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sunday summary", style = MaterialTheme.typography.titleMedium)
                Text("Sundays at 19:00: your week's averages and goals met", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            Switch(
                checked = settings.weekly,
                onCheckedChange = { on -> change { settings.weekly = on } },
                colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
            )
        }
    }

    editing?.let { meal ->
        val start = settings.timeFor(meal) ?: ReminderSettings.DEFAULTS.getValue(meal)
        val state = rememberTimePickerState(start / 60, start % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = ShokuColors.Paper,
            title = { Text("$meal reminder") },
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { change { settings.setTime(meal, state.hour * 60 + state.minute) }; editing = null }) { Text("Save", color = ShokuColors.Ink) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = ShokuColors.Muted) } },
        )
    }
}
