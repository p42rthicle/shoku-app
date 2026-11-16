package app.shoku.ui.components

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Meals
import app.shoku.domain.Nutrients
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.shortLabel
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.LocalDate

data class QuantityResult(val unit: String, val qty: Double, val status: String, val meal: String?, val date: LocalDate?, val variant: String? = null, val note: String? = null)

/**
 * Bottom sheet to pick an amount. Used to add an item and to edit a logged entry;
 * passing [meal] and [date] turns on the edit controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantitySheet(
    title: String,
    subtitle: String?,
    units: List<String>,
    initialUnit: String,
    initialQty: Double,
    initialStatus: String,
    nutrientsFor: (unit: String, qty: Double) -> Nutrients?,
    confirmLabel: String,
    onConfirm: (QuantityResult) -> Unit,
    onDismiss: () -> Unit,
    meal: String? = null,
    date: LocalDate? = null,
    onDelete: (() -> Unit)? = null,
    /** Shows "Replace with another food" (for a logged entry). */
    onReplace: (() -> Unit)? = null,
    /** Opens the food, dish or batch itself, labelled [openLabel] ("Edit food"). */
    onOpen: (() -> Unit)? = null,
    openLabel: String = "Edit food",
    askVariant: Boolean = false,
    /** Shows a Note field (where or how: "Bikanervala, Karol Bagh"); only when logging. */
    askNote: Boolean = false,
    initialNote: String? = null,
    /** Eaten / Planned only makes sense when logging; stock and meal templates hide it. */
    showStatus: Boolean = true,
    initialVariant: String? = null,
    /** Extra choices under the title (stock: food or supplement, and where it goes). */
    header: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var unit by remember { mutableStateOf(initialUnit) }
    val initialText = formatQty(initialQty)
    var text by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length))) }
    var note by remember { mutableStateOf(initialNote ?: "") }
    var status by remember { mutableStateOf(initialStatus) }
    var chosenMeal by remember { mutableStateOf(meal) }
    var chosenDate by remember { mutableStateOf(date) }
    var variant by remember { mutableStateOf(initialVariant ?: "") }

    val qty = text.text.replace(',', '.').toDoubleOrNull()
    val nutrients = qty?.let { nutrientsFor(unit, it) }
    val valid = qty != null && qty > 0

    /**
     * The same amount in another unit, worked out from calories per unit (1 slice = 20 g when both give
     * the same kcal). Null when calories are unknown or zero.
     */
    fun convert(from: String, q: Double, to: String): Double? {
        val a = nutrientsFor(from, 1.0)?.kcal ?: return null
        val b = nutrientsFor(to, 1.0)?.kcal?.takeIf { it > 0 } ?: return null
        return q * a / b
    }
    fun rounded(v: Double, u: String) = if (u in setOf("g", "ml")) Math.round(v).toDouble() else Math.round(v * 10) / 10.0

    fun confirm() {
        if (valid) onConfirm(QuantityResult(unit, qty!!, status, chosenMeal, chosenDate, variant.trim().ifEmpty { null }, note.trim().ifEmpty { null }))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(ShokuColors.Line))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = ShokuColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            if (header != null) { Spacer(Modifier.height(14.dp)); header() }
            Spacer(Modifier.height(18.dp))

            // Amount
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ShokuShapes.Card)
                    .background(ShokuColors.Tint)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { v -> if (v.text.length <= 7 && v.text.all { it.isDigit() || it == '.' || it == ',' }) text = v },
                    textStyle = MaterialTheme.typography.displayMedium.copy(color = ShokuColors.Ink),
                    singleLine = true,
                    cursorBrush = SolidColor(ShokuColors.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (unit == "serving") (if (qty == 1.0) "serving" else "servings") else unit,
                    style = MaterialTheme.typography.titleLarge,
                    color = ShokuColors.Muted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            // The same amount in the other kind of unit: grams under slices, slices under grams.
            val other = if (unit in setOf("g", "ml")) units.firstOrNull { it !in setOf("g", "ml") } else units.firstOrNull { it in setOf("g", "ml") }
            val same = if (other != null && qty != null) convert(unit, qty, other)?.let { rounded(it, other) } else null
            if (other != null && same != null && same > 0) {
                Text("= ${formatUnitQty(same, other)}", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickAmounts(unit, initialQty.takeIf { unit == initialUnit }).forEach { a ->
                    val s = formatQty(a)
                    Box(Modifier.clip(CircleShape).clickable { text = TextFieldValue(s, TextRange(s.length)) }) {
                        StatusChip(s, if (qty == a) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
            }

            if (askNote) {
                Spacer(Modifier.height(12.dp))
                LabeledField("Note (optional)", note, { note = it }, Modifier.fillMaxWidth(), placeholder = "e.g. restaurant, how it was made")
            }
            if (askVariant) {
                Spacer(Modifier.height(12.dp))
                LabeledField("Brand (optional)", variant, { variant = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Weihenstephan")
            }

            if (units.size > 1) {
                Spacer(Modifier.height(14.dp))
                Segmented(units.map { if (it == "serving") "Servings" else it }, units.indexOf(unit).coerceAtLeast(0)) {
                    val newUnit = units[it]
                    // Switching units keeps the amount (1 slice -> 20 g); without calories to convert by,
                    // start from 1 portion or 100 g.
                    if (newUnit != unit) {
                        val converted = qty?.let { convert(unit, it, newUnit) }?.let { rounded(it, newUnit) }?.takeIf { it > 0 }
                        val s = converted?.let { formatQty(it) }
                            ?: if (newUnit == initialUnit) formatQty(initialQty) else if (newUnit in setOf("g", "ml")) "100" else "1"
                        text = TextFieldValue(s, TextRange(0, s.length))
                    }
                    unit = newUnit
                }
            }

            // Live nutrients
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                NutrientStat("kcal", nutrients?.kcal, Modifier.weight(1f))
                NutrientStat("protein", nutrients?.protein, Modifier.weight(1f), suffix = " g")
                NutrientStat("fibre", nutrients?.fibre, Modifier.weight(1f), suffix = " g")
            }
            if (nutrients?.carbs != null || nutrients?.fat != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    NutrientStat("carbs", nutrients.carbs, Modifier.weight(1f), suffix = " g")
                    NutrientStat("fat", nutrients.fat, Modifier.weight(1f), suffix = " g")
                    Spacer(Modifier.weight(1f))
                }
            }

            if (chosenMeal != null) {
                Spacer(Modifier.height(18.dp))
                Text("Meal", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                Spacer(Modifier.height(8.dp))
                val settings = app.shoku.data.MealSettingsStore.current.value
                val meals = Meals.ALL.filter { m -> m == chosenMeal || chosenDate == null || !settings.hiddenOn(m, chosenDate!!) }
                Segmented(meals, meals.indexOf(chosenMeal).coerceAtLeast(0)) { chosenMeal = meals[it] }
            }
            if (chosenDate != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Day", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day", onClick = { chosenDate = chosenDate!!.minusDays(1) })
                    var pickingDay by remember { mutableStateOf(false) }
                    // Tap the date for a calendar (a week back is one tap, not seven).
                    Text(chosenDate!!.shortLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(120.dp).clip(ShokuShapes.Button).clickable { pickingDay = true }.padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (pickingDay) DatePick(chosenDate!!, onPick = { chosenDate = it }, onDismiss = { pickingDay = false })
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day", onClick = { chosenDate = chosenDate!!.plusDays(1) })
                }
            }

            if (showStatus) {
                Spacer(Modifier.height(18.dp))
                Segmented(listOf("Eaten", "Planned"), if (status == EntryStatus.EATEN) 0 else 1) {
                    status = if (it == 0) EntryStatus.EATEN else EntryStatus.PLANNED
                }
            }

            Spacer(Modifier.height(18.dp))
            if (onOpen != null) {
                SecondaryButton(openLabel, onClick = onOpen)
                Spacer(Modifier.height(10.dp))
            }
            if (onReplace != null) {
                SecondaryButton("Replace with another food", onClick = onReplace)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    // Labelled: a bare bin icon was easy to miss.
                    Row(
                        Modifier.height(54.dp).clip(ShokuShapes.Button).border(1.dp, ShokuColors.Line, ShokuShapes.Button).clickable(onClick = onDelete).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = ShokuColors.Ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink)
                    }
                }
                PrimaryButton(
                    text = if (valid) "$confirmLabel · ${formatUnitQty(qty!!, unit)}" else confirmLabel,
                    onClick = ::confirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NutrientStat(label: String, value: Double?, modifier: Modifier, suffix: String = "") {
    Column(modifier) {
        Text(value?.let { formatQty(if (label == "kcal") Math.round(it).toDouble() else Math.round(it * 10) / 10.0) + suffix } ?: "–", style = NumberStyle.Stat)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
    }
}

/** Pill-shaped segmented control in the monochrome style. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // The filled pill slides to the chosen option on a spring; labels fade their colour.
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(ShokuColors.Tint)
            .padding(4.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val w = maxWidth / n
        val x by androidx.compose.animation.core.animateDpAsState(w * selected.coerceIn(0, n - 1), app.shoku.ui.theme.ShokuMotion.spatial(), label = "segment")
        Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start).offset(x = x).width(w).clip(CircleShape).background(ShokuColors.Fill))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val color by androidx.compose.animation.animateColorAsState(if (on) ShokuColors.Paper else ShokuColors.Muted, app.shoku.ui.theme.ShokuMotion.effects(), label = "segmentText")
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable { onSelect(i) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
                }
            }
        }
    }
}

private fun quickAmounts(unit: String, usual: Double?): List<Double> {
    val base = when (unit) {
        "g", "ml" -> listOf(25.0, 50.0, 100.0, 150.0, 200.0, 250.0)
        "pc", "tin" -> listOf(0.5, 1.0, 2.0, 3.0, 4.0)
        "serving" -> listOf(0.5, 1.0, 1.5, 2.0)
        else -> listOf(0.5, 1.0, 1.5, 2.0, 3.0)
    }
    return (listOfNotNull(usual) + base).distinct()
}
