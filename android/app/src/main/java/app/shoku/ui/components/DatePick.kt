package app.shoku.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import app.shoku.ui.theme.ShokuColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A calendar to pick one day (instead of tapping a day arrow many times). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePick(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, confirm: String = "Set") {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(confirm, color = ShokuColors.Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ShokuColors.Muted) } },
    ) { DatePicker(state = state) }
}
