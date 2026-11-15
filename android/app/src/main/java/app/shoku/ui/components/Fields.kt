package app.shoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoku.domain.Nutrients
import app.shoku.ui.formatQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/** Tinted box with a small label and an input; numbers use the display font. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    suffix: String? = null,
    placeholder: String = "",
    /** Several lines (e.g. a list of rules); otherwise one line. */
    multiLine: Boolean = false,
) {
    val style: TextStyle = if (numeric) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
    Column(
        modifier
            .clip(ShokuShapes.Tile)
            .background(ShokuColors.Tint)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = ShokuColors.Planned)
                BasicTextField(
                    value = value,
                    onValueChange = { v -> if (!numeric || v.all { it.isDigit() || it == '.' || it == ',' }) onChange(v.replace(',', '.')) },
                    textStyle = style.copy(color = ShokuColors.Ink),
                    singleLine = !multiLine,
                    cursorBrush = SolidColor(ShokuColors.Ink),
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix != null) {
                Spacer(Modifier.width(4.dp))
                Text(suffix, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ShokuColors.Paper, checkedTrackColor = ShokuColors.Fill,
                uncheckedThumbColor = ShokuColors.Paper, uncheckedTrackColor = ShokuColors.Line, uncheckedBorderColor = ShokuColors.Line,
            ),
        )
    }
}

fun String.toAmount(): Double? = trim().replace(',', '.').toDoubleOrNull()
fun Double?.asField(): String = this?.let { formatQty(it) } ?: ""

/** Sheet to set an ingredient amount, with live nutrients and an optional remove button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountSheet(
    title: String,
    units: List<String>,
    initialUnit: String,
    initialQty: Double?,
    nutrientsFor: (String, Double) -> Nutrients?,
    onSave: (unit: String, qty: Double) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    /** "Replace with another food" (e.g. a wrong AI match). */
    onReplace: (() -> Unit)? = null,
) {
    var unit by remember { mutableStateOf(initialUnit) }
    var text by remember { mutableStateOf(initialQty.asField()) }
    val qty = text.toAmount()
    val n = qty?.let { nutrientsFor(unit, it) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            LabeledField("Amount", text, { text = it }, Modifier.fillMaxWidth(), numeric = true, suffix = if (unit == "serving") "servings" else unit)
            if (units.size > 1) {
                Spacer(Modifier.height(12.dp))
                Segmented(units.map { if (it == "serving") "Servings" else it }, units.indexOf(unit).coerceAtLeast(0)) { unit = units[it] }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                n?.let { "${it.kcal?.let { k -> formatQty(Math.round(k).toDouble()) } ?: "–"} kcal · ${it.protein?.let { p -> formatQty(Math.round(p * 10) / 10.0) } ?: "–"} g protein" } ?: " ",
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(16.dp))
            if (onReplace != null) {
                SecondaryButton("Replace with another food", onClick = onReplace)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRemove != null) {
                    Box(
                        Modifier.size(54.dp).clip(ShokuShapes.Button).border(1.dp, ShokuColors.Line, ShokuShapes.Button).clickable(onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Delete, "Remove", tint = ShokuColors.Ink) }
                    Spacer(Modifier.width(10.dp))
                }
                PrimaryButton("Save", onClick = { if (qty != null && qty > 0) onSave(unit, qty) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SmallIconCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(ShokuColors.Tint).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}
