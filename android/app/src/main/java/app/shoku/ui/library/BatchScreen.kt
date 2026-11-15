package app.shoku.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Batch
import app.shoku.domain.IngredientRef
import app.shoku.domain.asRef
import app.shoku.domain.fractionLeft
import app.shoku.ui.components.AmountSheet
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.MacroBar
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.asField
import app.shoku.ui.components.toAmount
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.shortLabel
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing
import java.time.LocalDate

/**
 * One cooked batch. With [cookFromDishId] it starts a new batch from the dish template;
 * with [batchId] it shows and edits a saved one. [onDone] gets the saved batch id, or null on back.
 */
@Composable
fun BatchScreen(
    vm: LibraryViewModel,
    data: LibraryData,
    batchId: String?,
    cookFromDishId: String?,
    contentPadding: PaddingValues,
    onDone: (String?) -> Unit,
    toast: (String) -> Unit,
) {
    val library = data.library
    val existing = batchId?.let { library.batches[it] }
    val dish = (existing?.dishId ?: cookFromDishId)?.let { library.dishes[it] }
    if (existing == null && dish == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(ShokuSpacing.Screen)) { Text("This batch no longer exists.", color = ShokuColors.Muted) }
        return
    }
    val isNew = existing == null

    // Draft state, initialised once per batch.
    val key = batchId ?: "new-$cookFromDishId"
    var date by remember(key) { mutableStateOf(existing?.date?.let { LocalDate.parse(it) } ?: LocalDate.now()) }
    var servings by remember(key) { mutableStateOf((existing?.servings ?: dish!!.servings).asField()) }
    val manualExisting = existing?.yieldSource == "manual"
    var finalQty by remember(key) { mutableStateOf(if (manualExisting) existing?.finalQty.asField() else "") }
    var finalUnit by remember(key) { mutableStateOf(existing?.finalUnit ?: dish?.yieldUnit ?: "g") }
    var notes by remember(key) { mutableStateOf(existing?.notes ?: "") }
    val ings = remember(key) {
        mutableStateListOf<IngredientRef>().apply {
            addAll(if (existing != null) library.batchIngredients[existing.id].orEmpty().map { it.asRef() } else library.dishIngredients[dish!!.id].orEmpty().map { it.asRef() })
        }
    }
    var picking by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<Pair<Int?, IngredientRef>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    var aiWorking by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    if (aiWorking) app.shoku.ui.components.BusyOverlay("Working out what went in…", onCancel = { aiJob?.cancel(); aiWorking = false })

    val s = servings.toAmount()?.takeIf { it > 0 } ?: 1.0
    val fq = finalQty.toAmount()?.takeIf { it > 0 }
    val preview = library.computeBatch(dish, s, ings.toList(), fq, if (fq != null) finalUnit else null)
    val fixedDish = dish?.fixed == true

    // What it looked like when opened: Back with changes asks before throwing them away.
    val start = remember(key) { listOf(date, servings, finalQty, finalUnit, notes) to ings.toList() }
    val dirty = (isNew && ings.isNotEmpty()) || start.first != listOf(date, servings, finalQty, finalUnit, notes) || start.second != ings.toList()
    var askLeave by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = dirty) { askLeave = true }

    fun draft(): Batch = (existing ?: Batch(
        id = ShokuRepository.newId(), dishId = dish!!.id, dishName = dish.name, date = date.toString(), servings = s,
        totalKcal = null, totalProtein = null, totalFibre = null,
    )).copy(date = date.toString(), servings = s, finalQty = fq, finalUnit = if (fq != null) finalUnit else null, notes = notes.trim())

    fun save() {
        if (isNew && dish != null && !dish.fixed && ings.isNotEmpty() && library.dishIngredients[dish.id].isNullOrEmpty()) {
            vm.saveDish(dish.copy(servings = s), ings.toList())
        }
        vm.saveBatch(draft(), ings.toList()) { id ->
            if (isNew) { toast("Batch saved"); onDone(id) } else toast("Saved. Entries from this batch updated")
        }
    }
    if (askLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(if (isNew) "Save this batch?" else "Save your changes?") },
            text = { Text(if (isNew) "It isn't saved yet." else "You changed this batch.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askLeave = false; save(); if (!isNew) onDone(null) }) { Text("Save", color = ShokuColors.Ink) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askLeave = false; onDone(null) }) { Text("Discard", color = ShokuColors.Muted) } },
            containerColor = ShokuColors.Paper,
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { if (dirty) askLeave = true else onDone(null) })
            Spacer(Modifier.height(14.dp))
            Text(if (isNew) "Cooking a new batch" else "Batch", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            Text(existing?.dishName ?: dish!!.name, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
        }

        item { PortionSummary(preview) }

        if (existing != null) {
            item {
                val (m, c) = data.consumption(existing)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    val left = c.remainingWeight?.let { "${formatQty(it)} ${m.finalUnit} left" } ?: "${formatUnitQty(c.remainingServings, "serving")} left"
                    Text(left, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Logged so far: ${formatUnitQty(Math.round(c.consumedServings * 100) / 100.0, "serving")}" +
                            (if (m.hasWeightBasis) " (${formatQty(c.consumedWeight)} ${m.finalUnit})" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    MacroBar(c.fractionLeft(m).toFloat(), track = ShokuColors.Tint)
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Finished", "Hide it from the fridge and from Add", existing.finished, { vm.setBatchFinished(existing, it) })
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cooked on", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before", onClick = { date = date.minusDays(1) })
                    Text(date.shortLabel(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after", onClick = { date = date.plusDays(1) })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("Servings", servings, { servings = it }, Modifier.weight(1f), numeric = true)
                    LabeledField("Cooked weight", finalQty, { finalQty = it }, Modifier.weight(1f), numeric = true, placeholder = "weigh it")
                    LabeledField("Unit", finalUnit, { finalUnit = it.trim() }, Modifier.weight(0.7f))
                }
                if (fq == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (preview.yieldSource == "auto") "No weight entered: using the ingredient total of ${formatQty(preview.finalQty!!)} ${preview.finalUnit}. Weigh the pot for accurate per-gram logging."
                        else "Add the cooked weight (you can do it later) to log this batch by grams.",
                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LabeledField("Notes", notes, { notes = it }, Modifier.fillMaxWidth(), placeholder = "e.g. a bit watery, extra chilli")
            }
        }

        if (fixedDish) {
            item { Text("This dish has fixed macros, so the batch uses them × servings.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
        } else {
            item { Spacer(Modifier.height(4.dp)); SectionLabel("What went in", trailing = if (isNew) "from the recipe, adjust as cooked" else null) }
            item {
                OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                    ings.forEachIndexed { i, ing ->
                        if (i > 0) Hairline()
                        IngredientRow(library, ing) { amountFor = i to ing }
                    }
                    if (ings.isNotEmpty()) Hairline()
                    AddRow("Add ingredient") { picking = true }
                    Hairline()
                    AddRow(if (aiWorking) "Working it out…" else "Describe what went in (AI)") { if (!aiWorking) describing = true }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (isNew) "Save batch" else "Save changes", onClick = { save() })
        }

        if (existing != null) {
            item {
                Text(
                    if (confirmDelete) "Tap again to delete. Entries already logged keep their numbers." else "Delete batch",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (confirmDelete) ShokuColors.Over else ShokuColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (confirmDelete) { vm.deleteBatch(existing); toast("Batch deleted"); onDone(null) } else confirmDelete = true
                    }.padding(vertical = 12.dp),
                )
            }
        }
    }

    if (describing) {
        app.shoku.ui.add.DescribeSheet(
            title = "What went into the pot?",
            hint = "Whole-pot amounts. Matched to your foods; anything new is added from an estimate.",
            placeholder = "e.g. 250 g rajma soaked, 2 onions, 2 tomatoes, 2 tbsp oil, makes 4 servings",
            button = "Fill ingredients",
            onSubmit = { text ->
                describing = false
                aiWorking = true
                aiJob = vm.aiRecipe(library, text, done = { refs, aiServings, created ->
                    aiWorking = false
                    ings.addAll(refs)
                    aiServings?.takeIf { it > 0 }?.let { servings = it.asField() }
                    toast(
                        "Added ${refs.size} ingredients" +
                            if (created.isNotEmpty()) ". New from estimates: ${created.joinToString()}, check them in Library" else "",
                    )
                }, fail = { msg -> aiWorking = false; toast(msg) })
            },
            onDismiss = { describing = false },
        )
    }

    if (picking) {
        ItemPicker(library, "Add ingredient", excludeDishId = dish?.id, onPick = { type, id, lib ->
            picking = false
            val unit = lib.defaultUnit(type, id) ?: return@ItemPicker
            amountFor = null to IngredientRef(type, id, unit, 0.0)
        }, onDismiss = { picking = false })
    }
    amountFor?.let { (index, ing) ->
        AmountSheet(
            title = library.nameOf(ing.refType, ing.refId) ?: "Ingredient",
            units = library.unitsFor(ing.refType, ing.refId).ifEmpty { listOf(ing.unit) },
            initialUnit = ing.unit,
            initialQty = ing.qty.takeIf { it > 0 },
            nutrientsFor = { u, q -> library.entryNutrients(ing.refType, ing.refId, u, q) },
            onSave = { u, q ->
                val updated = ing.copy(unit = u, qty = q)
                if (index == null) ings.add(updated) else ings[index] = updated
                amountFor = null
            },
            onRemove = index?.let { i -> { ings.removeAt(i); amountFor = null } },
            onDismiss = { amountFor = null },
        )
    }
}
