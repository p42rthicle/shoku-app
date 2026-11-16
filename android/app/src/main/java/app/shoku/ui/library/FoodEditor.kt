package app.shoku.ui.library

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import androidx.compose.ui.Alignment
import app.shoku.domain.Library
import app.shoku.domain.suggestFamilies
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.asField
import app.shoku.ui.components.toAmount
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/** Generic foods to offer as "Belongs to": the current one plus the best name matches. */
fun familyChoicesFor(library: Library, text: String, currentParentId: String?): List<Food> =
    (listOfNotNull(currentParentId?.let { library.foods[it] }) + library.suggestFamilies(text)).distinctBy { it.id }

/**
 * Create or edit a food. Nutrition is entered per base amount, like the label (per 100 g, per 1 pc).
 * [prefill] seeds a new food (e.g. from a barcode scan); [familyChoices] are generics it can belong to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodEditor(
    food: Food?,
    usedIn: Int,
    onSave: (Food, Boolean) -> Unit,
    onArchive: (() -> Unit)?,
    onDismiss: () -> Unit,
    prefill: Food? = null,
    familyChoices: List<Food> = emptyList(),
    title: String? = null,
    note: String? = null,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
    /** Shown under the note (a new food: "Already in your library?"). */
    header: (@Composable () -> Unit)? = null,
    /** The food's own portions; with [onSavePortions] set, they can be edited here. */
    portions: List<FoodPortion> = emptyList(),
    /** Portions it gets from its generic (read-only here). */
    inherited: List<FoodPortion> = emptyList(),
    inheritedFrom: String? = null,
    onSavePortions: ((List<FoodPortion>) -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    /** Only for a food used nowhere (never logged, in no dish, batch, plan or stock): removes it for good. */
    onDelete: (() -> Unit)? = null,
    /** Units worth offering (the pack's serving), added only if tapped. */
    suggested: List<FoodPortion> = emptyList(),
) {
    val seed = food ?: prefill
    var name by remember { mutableStateOf(seed?.name ?: "") }
    var brand by remember { mutableStateOf(seed?.brand ?: "") }
    var unit by remember { mutableStateOf(seed?.unit ?: "g") }
    var base by remember { mutableStateOf(seed?.baseQty.asField().ifEmpty { "100" }) }
    var kcal by remember { mutableStateOf(seed?.kcal.asField()) }
    var protein by remember { mutableStateOf(seed?.protein.asField()) }
    var fibre by remember { mutableStateOf(seed?.fibre.asField()) }
    var carbs by remember { mutableStateOf(seed?.carbs.asField()) }
    var fat by remember { mutableStateOf(seed?.fat.asField()) }
    var aliases by remember { mutableStateOf(seed?.aliases ?: "") }
    var parentId by remember { mutableStateOf(seed?.parentId) }
    var updatePast by remember { mutableStateOf(true) }
    // Editable copy of the portions: label to amount text. Removed ones are soft-deleted on save.
    var units by remember { mutableStateOf(portions.filter { !it.deleted }.sortedBy { it.qty }.map { Triple<String?, String, String>(it.id, it.label, it.qty.asField()) }) }
    var newLabel by remember { mutableStateOf("") }
    var newAmount by remember { mutableStateOf("") }
    val isGeneric = seed?.kind == FoodKind.GENERIC
    // A brand typed on an "any brand" food saves that brand's version as its own food in the family.
    val brandVersion = food != null && isGeneric && brand.isNotBlank()

    val valid = name.isNotBlank() && unit.isNotBlank() && (base.toAmount() ?: 0.0) > 0 && kcal.toAmount() != null && protein.toAmount() != null

    // Unsaved edits: Back or swiping the sheet away asks first ("I removed a unit and lost it").
    val start = remember { listOf(name, brand, unit, base, kcal, protein, fibre, carbs, fat, aliases, parentId.toString(), units.toString()) }
    val dirty = listOf(name, brand, unit, base, kcal, protein, fibre, carbs, fat, aliases, parentId.toString(), units.toString()) != start
    val dirtyNow by androidx.compose.runtime.rememberUpdatedState(dirty)
    var askSave by remember { mutableStateOf(false) }
    val saveRef = remember { arrayOfNulls<() -> Unit>(1) }
    if (askSave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askSave = false },
            containerColor = ShokuColors.Paper,
            title = { Text("Save changes?") },
            text = { Text(if (valid) "You changed this food but didn't save." else "You changed this food, but it still needs a name, calories and protein.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { askSave = false; if (valid) saveRef[0]?.invoke() }) { Text(if (valid) "Save" else "Keep editing", color = ShokuColors.Ink) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askSave = false; onDismiss() }) { Text("Discard", color = ShokuColors.Muted) }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = { if (dirtyNow) askSave = true else onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { v -> if (v == androidx.compose.material3.SheetValue.Hidden && dirtyNow) { askSave = true; false } else true },
        ),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title ?: if (food == null) "New food" else "Edit food", style = MaterialTheme.typography.headlineSmall)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            header?.invoke()
            extraActions.forEach { extraAction ->
                app.shoku.ui.components.SecondaryButton(extraAction.first, onClick = extraAction.second)
            }
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Kadhi pakora")
            LabeledField("Brand (optional)", brand, { brand = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Haldiram's")
            if (brandVersion) {
                Text(
                    "\"${food!!.name}\" stays as the any-brand entry. This saves ${brand.trim()}'s as its own food in the same family; adjust the values to its pack.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Nutrition per", base, { base = it }, Modifier.weight(1f), numeric = true)
                LabeledField("Unit", unit, { unit = it.trim() }, Modifier.weight(1f), placeholder = "g, ml, pc")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Calories", kcal, { kcal = it }, Modifier.weight(1f), numeric = true, suffix = "kcal")
                LabeledField("Protein", protein, { protein = it }, Modifier.weight(1f), numeric = true, suffix = "g")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Fibre", fibre, { fibre = it }, Modifier.weight(1f), numeric = true, suffix = "g")
                LabeledField("Carbs", carbs, { carbs = it }, Modifier.weight(1f), numeric = true, suffix = "g")
                LabeledField("Fat", fat, { fat = it }, Modifier.weight(1f), numeric = true, suffix = "g")
            }
            LabeledField("Also known as (optional)", aliases, { aliases = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Vollmilch, Milch")

            if (!isGeneric && familyChoices.isNotEmpty()) {
                Text("Belongs to", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    familyChoices.forEach { g ->
                        Box(Modifier.clip(CircleShape).clickable { parentId = if (parentId == g.id) null else g.id }) {
                            StatusChip(g.name, if (parentId == g.id) ChipStyle.Filled else ChipStyle.Tinted)
                        }
                    }
                    Box(Modifier.clip(CircleShape).clickable { parentId = null }) {
                        StatusChip("None", if (parentId == null) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
            }

            if (onSavePortions != null) {
                Text("Units", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                Text("How much of one is, in $unit. Lets you log \"2 tbsp\" or \"1 slice\".", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                units.forEachIndexed { i, (id, label, amount) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1 $label =", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        LabeledField("", amount, { v -> units = units.toMutableList().also { it[i] = Triple(id, label, v) } }, Modifier.weight(1f), numeric = true, suffix = unit)
                        Text("Remove", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                            modifier = Modifier.clip(ShokuShapes.Button).clickable { units = units.filterIndexed { j, _ -> j != i } }.padding(8.dp))
                    }
                }
                val offers = suggested.filter { s -> units.none { it.second == s.label } && s.label != unit }
                if (offers.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("From the pack:", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        offers.forEach { s ->
                            Box(Modifier.clip(CircleShape).clickable { units = units + Triple(null, s.label, app.shoku.ui.formatQty(s.qty)) }) {
                                StatusChip("+ 1 ${s.label} = ${app.shoku.ui.formatQty(s.qty)} $unit", ChipStyle.Tinted)
                            }
                        }
                    }
                }
                inherited.filter { p -> units.none { it.second == p.label } }.forEach { p ->
                    Text("1 ${p.label} = ${app.shoku.ui.formatQty(p.qty)} $unit · from ${inheritedFrom ?: "its family"}", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("New unit", newLabel, { newLabel = it }, Modifier.weight(1f), placeholder = "tbsp, slice")
                    LabeledField("Amount", newAmount, { newAmount = it }, Modifier.weight(1f), numeric = true, suffix = unit)
                    Text("Add", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink,
                        modifier = Modifier.clip(ShokuShapes.Button).clickable {
                            val l = newLabel.trim().lowercase()
                            if (l.isNotEmpty() && l != unit && (newAmount.toAmount() ?: 0.0) > 0 && units.none { it.second == l }) {
                                units = units + Triple(null, l, newAmount)
                                newLabel = ""; newAmount = ""
                            }
                        }.padding(12.dp))
                }
            }

            if (food != null && food.unit != unit) {
                Text("Changing the unit makes this a different food for amounts already logged in ${food.unit}.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Over)
            }
            if (food != null && !brandVersion) {
                SwitchRow(
                    "Update past entries",
                    if (usedIn > 0) "Recalculate the $usedIn entries and any batches that use this food" else "Recalculate batches that use this food",
                    updatePast, { updatePast = it },
                )
            }
            Spacer(Modifier.height(4.dp))
            val missing = listOfNotNull(
                "a name".takeIf { name.isBlank() }, "calories".takeIf { kcal.toAmount() == null }, "protein".takeIf { protein.toAmount() == null },
            )
            PrimaryButton(when {
                missing.isNotEmpty() -> "Add ${missing.joinToString(", ").replace(Regex(", ([^,]+)$"), " and $1")} first"
                food == null -> "Add food"
                brandVersion -> "Save ${brand.trim()}'s version"
                else -> "Save"
            }, onClick = { saveRef[0]?.invoke() })
            saveRef[0] = save@{
                if (!valid) return@save
                val saved = (food ?: prefill ?: Food(id = ShokuRepository.newId(), name = "", unit = "", baseQty = 1.0, kcal = null, protein = null, fibre = null)).copy(
                    id = food?.id ?: prefill?.id ?: ShokuRepository.newId(),
                    name = name.trim(), unit = unit, baseQty = base.toAmount()!!,
                    kcal = kcal.toAmount(), protein = protein.toAmount(), fibre = fibre.toAmount(), carbs = carbs.toAmount(), fat = fat.toAmount(),
                    aliases = aliases.trim(), parentId = if (isGeneric) null else parentId,
                    brand = brand.trim().ifEmpty { null },
                ).let { f ->
                    if (!brandVersion) f
                    else f.copy(id = ShokuRepository.newId(), kind = FoodKind.PRODUCT, parentId = food!!.id, barcode = null, source = "manual")
                }
                onSavePortions?.let { save ->
                    val now = System.currentTimeMillis()
                    val kept = units.mapNotNull { (id, label, amount) ->
                        val qty = amount.toAmount()?.takeIf { it > 0 } ?: return@mapNotNull null
                        val old = portions.firstOrNull { it.id == id }
                        if (food != null && old != null && old.qty == qty) null
                        else old?.copy(qty = qty, updatedAt = now)
                            ?: FoodPortion(java.util.UUID.nameUUIDFromBytes("portion|${saved.id}|$label".toByteArray()).toString(), saved.id, label, qty, updatedAt = now)
                    }
                    val removed = portions.filter { p -> !p.deleted && units.none { it.first == p.id } }.map { it.copy(deleted = true, updatedAt = now) }
                    if (kept.isNotEmpty() || removed.isNotEmpty()) save(kept + removed)
                }
                onSave(saved, updatePast && !brandVersion)
            }
            if (food?.archived == true && onUnarchive != null) {
                Text(
                    "Archived: hidden from Add and search. Tap to bring it back.", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable(onClick = onUnarchive).padding(vertical = 12.dp),
                )
            } else if (onArchive != null) {
                Text(
                    "Archive this food",
                    style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable(onClick = onArchive).padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
            if (onDelete != null) {
                var confirm by remember { mutableStateOf(false) }
                Text(
                    if (confirm) "Tap again to delete it" else "Delete this food (it isn't used anywhere)",
                    style = MaterialTheme.typography.labelLarge, color = if (confirm) ShokuColors.Over else ShokuColors.Muted,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { if (confirm) onDelete() else confirm = true }.padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
