package app.shoku.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.Dish
import app.shoku.domain.IngredientRef
import app.shoku.domain.Library
import app.shoku.domain.PortionMetrics
import app.shoku.domain.asRef
import app.shoku.domain.fractionLeft
import app.shoku.ui.components.AmountSheet
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.SummaryCard
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.asField
import app.shoku.ui.components.toAmount
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing
import java.time.LocalDate

@Composable
fun DishScreen(
    vm: LibraryViewModel,
    data: LibraryData,
    dishId: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onCook: () -> Unit,
    onOpenBatch: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    toast: (String) -> Unit,
) {
    val library = data.library
    val dish = library.dishes[dishId]
    if (dish == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(ShokuSpacing.Screen)) { Text("This dish no longer exists.", color = ShokuColors.Muted) }
        return
    }
    val ings = library.dishIngredients[dish.id].orEmpty().map { it.asRef() }
    val m = library.dishMetrics(dish)
    var editingDetails by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<Pair<Int?, IngredientRef>?>(null) } // index (null = new) to ingredient
    var describing by remember { mutableStateOf(false) }
    var showAllBatches by remember { mutableStateOf(false) }
    var aiWorking by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    if (aiWorking) app.shoku.ui.components.BusyOverlay("Working out the recipe…", onCancel = { aiJob?.cancel(); aiWorking = false })

    var updatePast by rememberSaveable { mutableStateOf(false) }

    fun save(newDish: Dish = dish, newIngs: List<IngredientRef> = ings) =
        vm.saveDish(newDish, newIngs, updatePast) { n -> toast(if (n > 0) "Saved. $n past entries updated" else "Saved") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                SquareIconButton(Icons.Outlined.ContentCopy, "Duplicate", onClick = { vm.duplicateDish(dish) { id -> toast("Duplicated"); onOpenDish(id) } })
                Spacer(Modifier.width(8.dp))
                SquareIconButton(
                    if (dish.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    if (dish.archived) "Unarchive" else "Archive",
                    onClick = { vm.setDishArchived(dish, !dish.archived); toast(if (dish.archived) "Unarchived" else "Archived") },
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(dish.name, style = MaterialTheme.typography.headlineLarge)
            Text(if (dish.fixed) "Dish with fixed macros" else "Recipe · ${ings.size} ingredients", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            Spacer(Modifier.height(4.dp))
        }

        item { PortionSummary(m) }

        item { PrimaryButton("Cook a batch", onClick = onCook) }

        item {
            OutlinedCard(Modifier.fillMaxWidth(), onClick = { editingDetails = true }) {
                DetailLine("Makes", formatUnitQty(dish.servings, "serving"))
                Hairline(Modifier.padding(vertical = 10.dp))
                DetailLine(
                    "Cooked weight",
                    when (m.yieldSource) {
                        "manual" -> "${formatQty(m.finalQty!!)} ${m.finalUnit}"
                        "auto" -> "${formatQty(m.finalQty!!)} ${m.finalUnit} (sum of ingredients)"
                        else -> "Not set"
                    },
                )
                if (dish.fixed) {
                    Hairline(Modifier.padding(vertical = 10.dp))
                    DetailLine("Per serving", "${dish.fixedKcal.asField()} kcal · ${dish.fixedProtein.asField()} g protein")
                }
                Spacer(Modifier.height(8.dp))
                Text("Tap to edit", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }

        if (!dish.fixed) {
            item { Spacer(Modifier.height(4.dp)); SectionLabel("Ingredients", trailing = "for the whole recipe") }
            item {
                OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                    ings.forEachIndexed { i, ing ->
                        if (i > 0) Hairline()
                        IngredientRow(library, ing) { amountFor = i to ing }
                    }
                    if (ings.isNotEmpty()) Hairline()
                    AddRow("Add ingredient") { picking = true }
                    Hairline()
                    AddRow(if (aiWorking) "Working it out…" else "Describe the recipe (AI)") { if (!aiWorking) describing = true }
                }
            }
            item {
                SwitchRow(
                    "Update past entries",
                    if (updatePast) "Recipe changes also recalculate entries already logged from this dish"
                    else "Entries already logged keep their numbers. Batches always keep their own snapshot.",
                    updatePast, { updatePast = it },
                )
            }
        }

        val batches = library.batches.values.filter { it.dishId == dish.id && !it.deleted }.sortedByDescending { it.date }
        if (batches.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)); SectionLabel("Batches", trailing = "${batches.size}") }
            item {
                ListCard(batches.let { if (showAllBatches) it else it.take(3) }, key = { it.id }) { b ->
                    val (bm, c) = data.consumption(b)
                    Row(Modifier.fillMaxWidth().clickable { onOpenBatch(b.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(runCatching { LocalDate.parse(b.date).dayMonth() }.getOrDefault(b.date), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(formatUnitQty(b.servings, "serving"), bm.finalQty?.let { "${formatQty(it)} ${bm.finalUnit}" }, b.notes.takeIf { it.isNotBlank() }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(if (b.finished || c.fractionLeft(bm) < 0.02) "Done" else "${Math.round(c.fractionLeft(bm) * 100)}% left", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted)
                    }
                }
            }
            if (batches.size > 3) item(key = "batches-more") { app.shoku.ui.components.SeeMoreChip(batches.size, 3, showAllBatches, { showAllBatches = !showAllBatches }) }
        }
    }

    if (editingDetails) {
        DishDetailsSheet(dish, onSave = { save(newDish = it); editingDetails = false }, onDismiss = { editingDetails = false })
    }
    if (describing) {
        app.shoku.ui.add.DescribeSheet(
            title = "What goes into it?",
            hint = "Whole-recipe amounts. Matched to your foods; anything new is added from an estimate.",
            placeholder = "e.g. 250 g rajma, 2 onions, 2 tomatoes, 2 tbsp oil, makes 4 servings",
            button = "Fill ingredients",
            onSubmit = { text ->
                describing = false
                aiWorking = true
                aiJob = vm.aiRecipe(library, text, done = { refs, aiServings, created ->
                    aiWorking = false
                    save(newDish = aiServings?.takeIf { it > 0 }?.let { dish.copy(servings = it) } ?: dish, newIngs = ings + refs)
                    toast("Added ${refs.size} ingredients" + if (created.isNotEmpty()) ". New from estimates: ${created.joinToString()}, check them in Library" else "")
                }, fail = { msg -> aiWorking = false; toast(msg) })
            },
            onDismiss = { describing = false },
        )
    }
    if (picking) {
        ItemPicker(library, "Add ingredient", excludeDishId = dish.id, onPick = { type, id, lib ->
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
                save(newIngs = if (index == null) ings + updated else ings.toMutableList().also { it[index] = updated })
                amountFor = null
            },
            onRemove = index?.let { i -> { save(newIngs = ings.filterIndexed { j, _ -> j != i }); amountFor = null } },
            onDismiss = { amountFor = null },
        )
    }
}

/** Per-serving numbers, totals and per-weight numbers for a recipe or batch. */
@Composable
fun PortionSummary(m: PortionMetrics) {
    val ps = m.perServing
    SummaryCard(label = "Per serving", value = ps.kcal?.let { formatQty(Math.round(it).toDouble()) } ?: "–", trailing = "kcal") {
        Spacer(Modifier.height(4.dp))
        Text(
            "${ps.protein?.let { formatQty(Math.round(it * 10) / 10.0) } ?: "–"} g protein · ${ps.fibre?.let { formatQty(Math.round(it * 10) / 10.0) } ?: "–"} g fibre",
            style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        val t = m.totals
        Text(
            "Whole recipe: ${t.kcal?.let { formatQty(Math.round(it).toDouble()) } ?: "–"} kcal, ${t.protein?.let { formatQty(Math.round(it * 10) / 10.0) } ?: "–"} g protein, ${formatUnitQty(m.servings, "serving")}",
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
        )
        m.perWeightUnit?.let { pw ->
            val per100 = if (m.finalUnit == "g" || m.finalUnit == "ml") 100.0 else 1.0
            Text(
                "Per ${formatQty(per100)} ${m.finalUnit}: ${pw.kcal?.let { formatQty(Math.round(it * per100).toDouble()) } ?: "–"} kcal · one serving ≈ ${formatQty(Math.round(m.gramsPerServing!!).toDouble())} ${m.finalUnit}",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
        }
        if (t.kcal == null) {
            Text("Some ingredients are missing from the library, so totals are unknown.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Over)
        }
    }
}

@Composable
fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun IngredientRow(library: Library, ing: IngredientRef, onClick: () -> Unit) {
    val name = library.nameOf(ing.refType, ing.refId)
    val n = library.entryNutrients(ing.refType, ing.refId, ing.unit, ing.qty)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name ?: "Missing item", style = MaterialTheme.typography.titleMedium, color = if (name == null) ShokuColors.Over else ShokuColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (ing.refType != "food") "${ing.refType.replaceFirstChar { it.uppercase() }} · " else "") + formatUnitQty(ing.qty, ing.unit),
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(n?.kcal?.let { "${formatQty(Math.round(it).toDouble())} kcal" } ?: "–", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AddRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Add, null, tint = ShokuColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DishDetailsSheet(dish: Dish, onSave: (Dish) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(dish.name) }
    var servings by remember { mutableStateOf(dish.servings.asField()) }
    var yieldQty by remember { mutableStateOf(dish.yieldQty.asField()) }
    var yieldUnit by remember { mutableStateOf(dish.yieldUnit ?: "g") }
    var fixed by remember { mutableStateOf(dish.fixed) }
    var kcal by remember { mutableStateOf(dish.fixedKcal.asField()) }
    var protein by remember { mutableStateOf(dish.fixedProtein.asField()) }
    var fibre by remember { mutableStateOf(dish.fixedFibre.asField()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Dish details", style = MaterialTheme.typography.headlineSmall)
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
            LabeledField("Makes", servings, { servings = it }, Modifier.fillMaxWidth(), numeric = true, suffix = "servings")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Cooked weight (optional)", yieldQty, { yieldQty = it }, Modifier.weight(2f), numeric = true)
                LabeledField("Unit", yieldUnit, { yieldUnit = it.trim() }, Modifier.weight(1f))
            }
            SwitchRow("Fixed macros", "For restaurant dishes: type per-serving values instead of ingredients", fixed, { fixed = it })
            if (fixed) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("Calories", kcal, { kcal = it }, Modifier.weight(1f), numeric = true, suffix = "kcal")
                    LabeledField("Protein", protein, { protein = it }, Modifier.weight(1f), numeric = true, suffix = "g")
                    LabeledField("Fibre", fibre, { fibre = it }, Modifier.weight(1f), numeric = true, suffix = "g")
                }
            }
            PrimaryButton(when {
                name.isBlank() -> "Name it first"
                (servings.toAmount() ?: 0.0) <= 0 -> "Set how many servings first"
                fixed && (kcal.toAmount() == null || protein.toAmount() == null) -> "Add calories and protein first"
                else -> "Save"
            }, onClick = {
                val s = servings.toAmount()?.takeIf { it > 0 } ?: return@PrimaryButton
                if (name.isBlank() || (fixed && (kcal.toAmount() == null || protein.toAmount() == null))) return@PrimaryButton
                val yq = yieldQty.toAmount()?.takeIf { it > 0 }
                onSave(
                    dish.copy(
                        name = name.trim(), servings = s, yieldQty = yq, yieldUnit = if (yq != null) yieldUnit else null, fixed = fixed,
                        fixedKcal = if (fixed) kcal.toAmount() else null, fixedProtein = if (fixed) protein.toAmount() else null, fixedFibre = if (fixed) fibre.toAmount() else null,
                    ),
                )
            })
        }
    }
}
