package app.shoku.ui.today

import androidx.compose.material.icons.outlined.PhotoCamera
import app.shoku.ui.components.cardPiece
import app.shoku.ui.components.CardPiece
import app.shoku.ui.dayMonth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.shoku.data.db.Basis
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import app.shoku.data.db.RefType
import app.shoku.domain.DayTotals
import app.shoku.domain.Library
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.EntryRow
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.IconTile
import app.shoku.ui.components.MacroBar
import app.shoku.ui.components.MacroLine
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.QuantitySheet
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SummaryCard
import app.shoku.ui.components.formatAmount
import app.shoku.ui.formatUnitQty
import app.shoku.ui.longLabel
import app.shoku.ui.relativeTitle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: TodayViewModel,
    library: Library?,
    snackbar: SnackbarHostState,
    contentPadding: PaddingValues,
    onAdd: (date: LocalDate, meal: String?) -> Unit,
    onCook: () -> Unit = {},
    /** Opens a dish or batch in Library (foods are edited right here). */
    onOpenItem: (refType: String, refId: String) -> Unit = { _, _ -> },
    /** Opens the global search. */
    onSearch: () -> Unit = {},
    /** Opens Plan > Supplements. */
    onOpenSupplements: () -> Unit = {},
    /** Opens Add with a batch's amount sheet (a fridge card). */
    onLogBatch: (date: LocalDate, batchId: String) -> Unit = { d, _ -> onAdd(d, null) },
) {
    val state by vm.state.collectAsState()
    var editingFood by remember { mutableStateOf<app.shoku.data.db.Food?>(null) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LogEntry?>(null) }
    var replacing by remember { mutableStateOf<LogEntry?>(null) }
    var replaceWith by remember { mutableStateOf<Pair<LogEntry, Pair<String, String>>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val context = androidx.compose.ui.platform.LocalContext.current
    var photoAt by remember { mutableStateOf<String?>(null) }
    // "Add a photo of this meal" (no estimate): the photo sheet, then saved with the meal's entries.
    var photoFor by remember { mutableStateOf<String?>(null) }
    photoFor?.let { mealName ->
        app.shoku.ui.add.PlateSheet(
            onEstimate = { photos, _ ->
                photoFor = null
                vm.attachPhotos(mealName, photos)
                android.widget.Toast.makeText(context, "Photo saved with ${mealName.lowercase()}", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { photoFor = null },
            title = "Photo of your ${mealName.lowercase()}",
            hint = "Saved with this meal's entries and in your meal photos. Nothing is estimated (no AI).",
            showNote = false,
            action = { n -> "Save ${if (n == 1) "photo" else "$n photos"}" },
        )
    }
    photoAt?.let { f -> app.shoku.ui.components.MealPhotoViewer(f, onDismiss = { photoAt = null }) }

    fun deleteWithUndo(entry: LogEntry) {
        vm.delete(entry)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // Long: enough time to reach Undo (a short one was gone in ~4 s).
            if (snackbar.showSnackbar("Deleted ${entry.name}", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                vm.restore(entry)
            }
        }
    }

    // Swipe left or right anywhere (except on an uneaten entry, which swipes to delete) to change day.
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 90.dp.toPx() }
    androidx.compose.animation.AnimatedContent(
        targetState = state,
        contentKey = { it.date },
        transitionSpec = {
            val forward = targetState.date.isAfter(initialState.date)
            (androidx.compose.animation.slideInHorizontally { w -> if (forward) w / 3 else -w / 3 } + androidx.compose.animation.fadeIn())
                .togetherWith(androidx.compose.animation.slideOutHorizontally { w -> if (forward) -w / 3 else w / 3 } + androidx.compose.animation.fadeOut())
        },
        label = "day",
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total <= -swipeThreshold) vm.shiftDay(1) else if (total >= swipeThreshold) vm.shiftDay(-1)
                    total = 0f
                },
                onDragCancel = { total = 0f },
                onHorizontalDrag = { _, dx -> total += dx },
            )
        },
    ) { state ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ShokuSpacing.Screen,
                    end = ShokuSpacing.Screen,
                    top = contentPadding.calculateTopPadding() + 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
            ) {
                item(key = "title") {
                    val d = state.date
                    ScreenTitle(
                        title = d.relativeTitle(today),
                        eyebrow = d.longLabel() + if (d.year != today.year) " ${d.year}" else "",
                        modifier = Modifier.clickable { picking = true },
                        actions = {
                            app.shoku.ui.search.GlobalSearchButton(onSearch)
                            SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day", onClick = { vm.shiftDay(-1) })
                            SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day", onClick = { vm.shiftDay(1) })
                        },
                    )
                    if (d != today) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.clip(CircleShape).clickable { vm.setDate(today) }) { StatusChip("Back to today", ChipStyle.Tinted) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Removing planned items: the whole day here, one meal in its card; eaten items stay; Undo brings them back.
                fun removePlanned(meal: String?) {
                    val gone = state.meals.filter { meal == null || it.meal == meal }.flatMap { it.entries }.filter { it.status != EntryStatus.EATEN }
                    if (gone.isEmpty()) return
                    vm.undoAll(gone)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        // Long: removing a whole day at once deserves time to undo.
                        val r = snackbar.showSnackbar("Removed ${gone.size} planned ${if (gone.size == 1) "item" else "items"}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) vm.restoreAll(gone)
                    }
                }
                item(key = "summary") { DaySummary(state, onRemovePlanned = { removePlanned(null) }) }
                // Supplements about to run out: time to order.
                if (state.runningLow.isNotEmpty()) item(key = "low") {
                    OutlinedCard(Modifier.fillMaxWidth(), onClick = onOpenSupplements) {
                        Text("Running low", style = MaterialTheme.typography.titleMedium)
                        state.runningLow.forEach { (name, until) ->
                            val by = until.minusDays(7)
                            Text("$name · lasts until ${until.dayMonth()} · ${if (!by.isAfter(LocalDate.now())) "order now" else "order by ${by.dayMonth()}"}",
                                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                        }
                    }
                }

                if (state.plan != null || state.planChoices.isNotEmpty()) {
                    val p = state.plan
                    item(key = "plan") {
                        PlanCard(p, state.planChoices, title = state.date.relativeTitle().let { t -> if (t.first().isDigit() || t.contains(' ')) "Plan for $t" else "$t's plan" }, onChoose = vm::choose, onCook = onCook, onAdd = add@{
                            p ?: return@add
                            vm.addPlan(p) { added ->
                                scope.launch {
                                    val r = snackbar.showSnackbar("Added ${added.size} planned items", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.undoAll(added)
                                }
                            }
                        })
                    }
                }
                state.usual?.takeIf { state.plan == null && state.planChoices.isEmpty() }?.let { u ->
                    item(key = "usual") {
                        UsualCard(u, onLogAll = {
                            vm.logUsual(u) { logged ->
                                scope.launch {
                                    val r = snackbar.showSnackbar("Logged your usual ${u.meal.lowercase()}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.undoAll(logged)
                                }
                            }
                        }, onPick = { onAdd(state.date, u.meal) })
                    }
                }

                if (state.fridge.isNotEmpty()) {
                    item(key = "fridge-label") {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("In the fridge", Modifier.weight(1f), trailing = "${state.fridge.size} ${if (state.fridge.size == 1) "batch" else "batches"}")
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.clip(CircleShape).clickable(onClick = onCook)) { StatusChip("+ Cook", ChipStyle.Tinted) }
                        }
                    }
                    item(key = "fridge") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.fridge, key = { it.batch.id }) { f ->
                                OutlinedCard(Modifier.width(168.dp), onClick = { onLogBatch(state.date, f.batch.id) }) {
                                    Text(f.batch.dishName, style = MaterialTheme.typography.titleMedium, color = ShokuColors.Ink, maxLines = 1)
                                    Text(f.left, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, maxLines = 1)
                                    Spacer(Modifier.height(12.dp))
                                    MacroBar(f.fraction, track = ShokuColors.Tint)
                                    if (f.ask) {
                                        Spacer(Modifier.height(10.dp))
                                        Text("Still in the fridge?", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(Modifier.clip(CircleShape).clickable { vm.keepBatch(f.batch) }) { StatusChip("Yes", ChipStyle.Tinted) }
                                            Box(Modifier.clip(CircleShape).clickable { vm.finishBatch(f.batch) }) { StatusChip("Finished", ChipStyle.Filled) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Each meal is a header, one item per entry and a footer, drawn as one card: a long dinner is
                // built a row at a time while scrolling instead of all in one frame (smooth at 120 Hz).
                state.meals.forEach { meal ->
                    item(key = "meal-${meal.meal}", contentType = "meal-head") {
                        MealHeader(meal, state.supplements[meal.meal].orEmpty(), onSupplement = { s, taken -> vm.toggleSupplement(s, taken) })
                    }
                    items(meal.entries, key = { "e-" + it.id }, contentType = { "entry" }) { entry ->
                        MealEntry(entry, library, onToggle = vm::toggle, onEdit = { editing = it }, onDelete = ::deleteWithUndo, onPhoto = { photoAt = it })
                    }
                    item(key = "meal-foot-${meal.meal}", contentType = "meal-foot") {
                        MealFooter(meal, onAdd = { onAdd(state.date, meal.meal) }, onRemovePlanned = { removePlanned(meal.meal) }, onPhoto = { photoFor = meal.meal })
                    }
                }
            }
    }

    editing?.let { entry ->
        val canRecompute = entry.refId != null && library?.perUnitOf(entry.refType, entry.refId, entry.unit) != null
        QuantitySheet(
            title = entry.name,
            subtitle = if (entry.refType == RefType.BATCH) "Batch" else null,
            units = if (canRecompute) library!!.unitsFor(entry.refType, entry.refId!!).ifEmpty { listOf(entry.unit) } else listOf(entry.unit),
            initialUnit = entry.unit,
            initialQty = entry.qty,
            initialStatus = entry.status,
            nutrientsFor = { unit, qty -> recompute(entry, library, unit, qty) },
            confirmLabel = "Save",
            meal = entry.meal,
            date = LocalDate.parse(entry.date),
            onConfirm = { r ->
                val same = r.unit == entry.unit && r.qty == entry.qty
                val n = if (same) app.shoku.domain.Nutrients(entry.kcal, entry.protein, entry.fibre, entry.carbs, entry.fat) else recompute(entry, library, r.unit, r.qty)
                vm.save(
                    entry.copy(
                        unit = r.unit, qty = r.qty, status = r.status, meal = r.meal ?: entry.meal, date = (r.date ?: LocalDate.parse(entry.date)).toString(),
                        variant = if (entry.basis == Basis.TYPICAL) r.variant else entry.variant,
                        note = r.note,
                        kcal = n?.kcal, protein = n?.protein, fibre = n?.fibre, carbs = n?.carbs, fat = n?.fat,
                    ),
                )
                editing = null
            },
            onDismiss = { editing = null },
            onDelete = { editing = null; deleteWithUndo(entry) },
            onReplace = if (library != null) ({ editing = null; replacing = entry }) else null,
            onOpen = entry.refId?.let { id ->
                when {
                    entry.refType == RefType.FOOD && library?.foods?.containsKey(id) == true -> ({ editing = null; editingFood = library.foods[id] })
                    entry.refType == RefType.DISH && library?.dishes?.containsKey(id) == true -> ({ editing = null; onOpenItem(RefType.DISH, id) })
                    entry.refType == RefType.BATCH && library?.batches?.containsKey(id) == true -> ({ editing = null; onOpenItem(RefType.BATCH, id) })
                    else -> null
                }
            },
            openLabel = when (entry.refType) { RefType.DISH -> "Open dish"; RefType.BATCH -> "Open batch"; else -> "Edit food" },
            askVariant = entry.basis == Basis.TYPICAL,
            initialVariant = entry.variant,
            askNote = true, initialNote = entry.note,
        )
    }

    // The food behind an entry, edited in place (brand, values, units), as in Library.
    editingFood?.let { f ->
        val lib = library ?: return@let
        val usedIn by androidx.compose.runtime.produceState(0, f.id) { value = vm.usageCount(f.id) }
        app.shoku.ui.library.FoodEditor(
            food = f,
            usedIn = usedIn,
            familyChoices = app.shoku.ui.library.familyChoicesFor(lib, f.name, f.parentId),
            portions = lib.portions[f.id].orEmpty(),
            inherited = lib.portionsOf(f).filter { it.foodId != f.id },
            inheritedFrom = f.parentId?.let { lib.foods[it]?.name },
            onSavePortions = { vm.savePortions(it) },
            onSave = { edited, updatePast ->
                val food = if (edited.source == "ai-estimate") edited.copy(source = "ai-checked") else edited
                vm.saveFood(food, updatePast) { n ->
                    scope.launch {
                        snackbar.showSnackbar(
                            when {
                                food.id != f.id -> "Added ${food.brand}'s ${food.name} to your library"
                                updatePast && n > 0 -> "Saved. $n past entries updated"
                                else -> "Saved"
                            },
                        )
                    }
                }
                editingFood = null
            },
            onArchive = null,
            onDismiss = { editingFood = null },
        )
    }

    // Replace an entry: search, pick, then confirm the amount for the new item.
    replacing?.let { entry ->
        val lib = library ?: return@let
        val reference = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.shoku.ShokuApplication).reference
        ReplaceSheet(entry, lib, reference, onPick = { pick ->
            replacing = null
            vm.resolvePick(pick) { type, id -> replaceWith = entry to (type to id) }
        }, onDismiss = { replacing = null })
    }
    replaceWith?.let { (entry, target) ->
        val lib = library ?: return@let
        val (type, id) = target
        val units = lib.unitsFor(type, id)
        if (units.isEmpty()) { replaceWith = null; return@let }
        val (startUnit, startQty) = app.shoku.domain.carryAmount(entry, lib, units)
        val food = if (type == RefType.FOOD) lib.foods[id] else null
        QuantitySheet(
            title = lib.nameOf(type, id) ?: entry.name,
            subtitle = "Replaces ${entry.name}",
            units = units,
            initialUnit = startUnit,
            initialQty = startQty,
            initialStatus = entry.status,
            nutrientsFor = { u, q -> lib.entryNutrients(type, id, u, q) },
            confirmLabel = "Replace",
            meal = entry.meal,
            date = LocalDate.parse(entry.date),
            onConfirm = { r ->
                val n = lib.entryNutrients(type, id, r.unit, r.qty)
                vm.save(
                    entry.copy(
                        refType = type, refId = id, name = lib.nameOf(type, id) ?: entry.name,
                        unit = r.unit, qty = r.qty, status = r.status, meal = r.meal ?: entry.meal, date = (r.date ?: LocalDate.parse(entry.date)).toString(),
                        kcal = n?.kcal, protein = n?.protein, fibre = n?.fibre, carbs = n?.carbs, fat = n?.fat,
                        basis = if (food?.kind == app.shoku.data.db.FoodKind.GENERIC) Basis.TYPICAL else Basis.EXACT,
                        variant = null,
                    ),
                )
                replaceWith = null
            },
            onDismiss = { replaceWith = null },
        )
    }

    if (picking) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Go", color = ShokuColors.Ink) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = ShokuColors.Muted) } },
            colors = DatePickerDefaults.colors(containerColor = ShokuColors.Paper),
        ) {
            DatePicker(
                state = pickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = ShokuColors.Paper,
                    selectedDayContainerColor = ShokuColors.Fill,
                    todayDateBorderColor = ShokuColors.Ink,
                    todayContentColor = ShokuColors.Ink,
                ),
            )
        }
    }
}

/** Nutrients for an edited entry: from the library when possible, otherwise scaled from the logged snapshot. */
private fun recompute(entry: LogEntry, library: Library?, unit: String, qty: Double): app.shoku.domain.Nutrients? {
    val ref = entry.refId
    if (ref != null && library != null) library.entryNutrients(entry.refType, ref, unit, qty)?.let { return it }
    if (unit != entry.unit || entry.qty <= 0) return null
    val f = qty / entry.qty
    return app.shoku.domain.Nutrients(entry.kcal?.times(f), entry.protein?.times(f), entry.fibre?.times(f), entry.carbs?.times(f), entry.fat?.times(f))
}

@Composable
private fun DaySummary(state: TodayState, onRemovePlanned: () -> Unit = {}) {
    val eaten = state.eaten ?: return
    val planned = state.planned!!
    val goal = state.goal
    var showMore by rememberSaveable { mutableStateOf(false) }

    val label: String
    val target: Float
    when {
        goal == null -> { label = "Calories eaten"; target = eaten.kcal.toFloat() }
        eaten.kcal <= goal.kcal -> { label = "Calories left"; target = (goal.kcal - eaten.kcal).toFloat() }
        else -> { label = "Calories over"; target = (eaten.kcal - goal.kcal).toFloat() }
    }
    // The big number counts to its new value instead of snapping.
    val shown by androidx.compose.animation.core.animateFloatAsState(target, app.shoku.ui.theme.ShokuMotion.slow(), label = "kcal")
    val value = formatAmount(Math.round(shown).toFloat())

    SummaryCard(
        label = label,
        value = value,
        trailing = goal?.let { "of ${formatAmount(it.kcal.toFloat())}" },
        valueColor = if (goal != null && eaten.kcal > goal.kcal) ShokuColors.Over else ShokuColors.Ink,
    ) {
        Spacer(Modifier.height(18.dp))
        MacroLine("Calories", eaten.kcal.toFloat(), goal?.kcal?.toFloat(), "kcal", planned = planned.kcal.toFloat(), isLimit = true)
        Spacer(Modifier.height(16.dp))
        MacroLine("Protein", eaten.protein.toFloat(), goal?.protein?.toFloat(), "g", planned = planned.protein.toFloat())
        Spacer(Modifier.height(16.dp))
        MacroLine("Fibre", eaten.fibre.toFloat(), goal?.fibre?.toFloat(), "g", planned = planned.fibre.toFloat())
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showMore = !showMore }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Carbs and fat", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
            Icon(
                if (showMore) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (showMore) "Hide carbs and fat" else "Show carbs and fat",
                tint = ShokuColors.Muted,
            )
        }
        AnimatedVisibility(showMore) {
            Column {
                Spacer(Modifier.height(6.dp))
                MacroLine("Carbs", eaten.carbs.toFloat(), null, "g")
                Spacer(Modifier.height(12.dp))
                MacroLine("Fat", eaten.fat.toFloat(), null, "g")
                missingNote(eaten)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        val footer = when {
            planned.count > 0 -> "Planned still to eat: ${formatAmount(planned.kcal.toFloat())} kcal, ${formatAmount(planned.protein.toFloat())} g protein."
            eaten.count == 0 -> "Nothing logged yet."
            else -> null
        }
        if (footer != null) {
            Spacer(Modifier.height(6.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(footer, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
                if (planned.count > 0) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clip(CircleShape).clickable(onClick = onRemovePlanned)) { StatusChip("Remove planned", ChipStyle.Tinted) }
                }
            }
        }
    }
}

private fun missingNote(t: DayTotals): String? {
    if (t.count == 0) return null
    val missing = maxOf(t.missingCarbs, t.missingFat)
    return when {
        missing == 0 -> null
        missing == t.count -> "No carb or fat values for these items yet. Foods from the old app only have calories, protein and fibre."
        else -> "Carbs and fat are missing for $missing of ${t.count} items, so these totals are low."
    }
}

private fun mealIcon(meal: String): ImageVector = when (meal) {
    Meals.BREAKFAST -> Icons.Outlined.BakeryDining
    Meals.LUNCH -> Icons.Outlined.LunchDining
    Meals.SNACKS -> Icons.Outlined.Cookie
    else -> Icons.Outlined.Restaurant
}

private fun entryDetail(e: LogEntry): String {
    val amount = formatUnitQty(e.qty, e.unit)
    val base = when (e.refType) {
        RefType.BATCH -> "Batch · $amount"
        RefType.DISH -> "Dish · $amount"
        else -> amount
    }
    return listOfNotNull(base, e.variant, if (e.basis == Basis.ESTIMATE) "estimate" else null, e.note?.let { "📝 $it" }).joinToString(" · ")
}

@Composable
private fun MealHeader(
    meal: MealUi,
    supplements: List<Pair<app.shoku.data.db.Supplement, Boolean>>,
    onSupplement: (app.shoku.data.db.Supplement, Boolean) -> Unit,
) {
    val count = meal.entries.size
    Column(Modifier.fillMaxWidth().cardPiece(CardPiece.Top, ShokuSpacing.Gap).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(mealIcon(meal.meal))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(meal.meal, style = MaterialTheme.typography.titleLarge, color = ShokuColors.Ink)
                Text(
                    if (count == 0) "Nothing yet" else "${formatAmount(meal.kcal.toFloat())} kcal · ${formatAmount(meal.protein.toFloat())} g protein",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShokuColors.Muted,
                )
            }
            when {
                count == 0 -> {}
                meal.eatenCount == count -> StatusChip("Eaten", ChipStyle.Filled)
                meal.eatenCount == 0 -> StatusChip("Planned", ChipStyle.Outlined)
                else -> StatusChip("${meal.eatenCount}/$count eaten", ChipStyle.Tinted)
            }
        }
        if (supplements.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // Supplements due with this meal: tap to mark taken (filled) or not yet (outlined).
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val notYet = supplements.filter { !it.second }
                if (notYet.size >= 2) {
                    Box(Modifier.clip(CircleShape).clickable { notYet.forEach { (s, _) -> onSupplement(s, false) } }) { StatusChip("✓ All taken", ChipStyle.Filled) }
                }
                supplements.forEach { (s, taken) ->
                    Box(Modifier.clip(CircleShape).clickable { onSupplement(s, taken) }) {
                        StatusChip((if (taken) "✓ " else "") + s.name + if (s.dose != 1.0 || s.unit == "g") " ${app.shoku.ui.formatUnitQty(s.dose, s.unit)}" else "", if (taken) ChipStyle.Filled else ChipStyle.Outlined)
                    }
                }
            }
        }
    }
}

@Composable
private fun MealEntry(entry: LogEntry, library: Library?, onToggle: (LogEntry) -> Unit, onEdit: (LogEntry) -> Unit, onDelete: (LogEntry) -> Unit, onPhoto: (String) -> Unit = {}) {
    val photo = remember(entry.photos) { app.shoku.data.PhotoStore.of(entry.photos).firstOrNull() }
    Column(Modifier.fillMaxWidth().cardPiece(CardPiece.Middle, ShokuSpacing.Gap).padding(horizontal = 18.dp)) {
        Hairline()
        // Planned entries swipe away; over an eaten one a sideways swipe changes the day (delete it from its sheet).
        SwipeToDelete(enabled = entry.status != EntryStatus.EATEN, onDelete = { onDelete(entry) }) {
            // Foods show their current name and brand chip; dishes, batches and quick entries keep the logged name.
            val food = if (entry.refType == RefType.FOOD) entry.refId?.let { library?.foods?.get(it) } else null
            EntryRow(
                name = food?.name ?: entry.name,
                tag = food?.brand ?: entry.variant,
                detail = entryDetail(entry),
                kcal = (entry.kcal ?: 0.0).toFloat(),
                proteinG = (entry.protein ?: 0.0).toFloat(),
                eaten = entry.status == EntryStatus.EATEN,
                onToggle = { onToggle(entry) },
                onClick = { onEdit(entry) },
                approx = entry.basis != Basis.EXACT,
                onPhoto = photo?.let { p -> { onPhoto(p) } },
            )
        }
    }
}

@Composable
private fun MealFooter(meal: MealUi, onAdd: () -> Unit, onRemovePlanned: () -> Unit, onPhoto: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().cardPiece(CardPiece.Bottom, ShokuSpacing.Gap).padding(start = 18.dp, end = 18.dp, bottom = 6.dp)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onAdd)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = ShokuColors.Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Add to ${meal.meal.lowercase()}", style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
            val plannedHere = meal.entries.size - meal.eatenCount
            if (plannedHere > 0) {
                Box(Modifier.clip(CircleShape).clickable(onClick = onRemovePlanned)) { StatusChip("Remove $plannedHere planned", ChipStyle.Tinted) }
            }
            // A photo of this meal, no AI: saved with its entries, in the meal photos.
            if (meal.entries.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(CircleShape).clickable(onClick = onPhoto).padding(8.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, "Add a photo of this meal", tint = ShokuColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Swipe left or right to delete (an Undo snackbar follows). Only planned entries: elsewhere a swipe changes the day. */
@Composable
internal fun SwipeToDelete(enabled: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShokuColors.Fill)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
            ) {
                Icon(Icons.Outlined.Delete, null, tint = ShokuColors.Paper, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Paper)
            }
        },
    ) {
        Box(Modifier.background(ShokuColors.Paper)) { content() }
    }
}

@Composable
fun PlaceholderScreen(title: String, note: String, contentPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(ShokuSpacing.Screen),
    ) {
        Column {
            ScreenTitle(title)
            Spacer(Modifier.height(20.dp))
            SummaryCard(label = "Not built yet", value = "Soon") {
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            }
        }
    }
}

/** "Your usual breakfast": everything you normally have, logged in one tap. */
@Composable
private fun UsualCard(u: UsualMeal, onLogAll: () -> Unit, onPick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your usual ${u.meal.lowercase()}", style = MaterialTheme.typography.titleLarge)
                Text(
                    u.items.joinToString(" · ") { "${it.name} ${app.shoku.ui.formatUnitQty(it.qty, it.unit)}" },
                    style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("${Math.round(u.kcal)} kcal", style = app.shoku.ui.theme.NumberStyle.Row)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.shoku.ui.components.PrimaryButton("Log all", onClick = onLogAll, modifier = Modifier.weight(1f))
            app.shoku.ui.components.SecondaryButton("Change", onClick = onPick, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * "Today's plan": each empty meal with the template it will use, switchable to any other template for
 * that meal (or left out) for today; then added in one tap as planned entries.
 */
@Composable
private fun PlanCard(
    p: app.shoku.domain.PlannedDay?,
    choices: List<PlanChoice>,
    title: String,
    onChoose: (String, String?) -> Unit,
    onAdd: () -> Unit,
    onCook: () -> Unit,
) {
    val entries = p?.entries.orEmpty()
    // Folded away (remembered across days): just the title, totals and an "Add as per plan" chip.
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("today_ui", android.content.Context.MODE_PRIVATE)
    var folded by remember { mutableStateOf(prefs.getBoolean("planFolded", false)) }
    fun toggle() { folded = !folded; prefs.edit().putBoolean("planFolded", folded).apply() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${Math.round(entries.sumOf { it.kcal ?: 0.0 })} kcal", style = app.shoku.ui.theme.NumberStyle.Row)
                Text("${Math.round(entries.sumOf { it.protein ?: 0.0 })} g protein", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (folded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = if (folded) "Show the plan" else "Hide the plan",
                tint = ShokuColors.Muted,
            )
        }
        if (folded && entries.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.clip(CircleShape).clickable(onClick = onAdd)) { StatusChip("Add as per plan · ${entries.size} items", ChipStyle.Tinted) }
        }
        AnimatedVisibility(!folded) { Column {
        Spacer(Modifier.height(6.dp))
        choices.forEach { c ->
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // What will actually be added (your fridge batches in place of "sabzi or dal"), not the template's name.
                val items = entries.filter { it.meal == c.meal }
                Column(Modifier.weight(1f)) {
                    Text(c.meal + (c.current?.let { " · ${it.name}" } ?: ""), style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        when {
                            c.current == null -> "Not adding"
                            items.isEmpty() -> "Nothing to add"
                            else -> items.joinToString(" · ") { e -> if (e.refType == app.shoku.data.db.RefType.BATCH) "${e.name} (fridge)" else e.name }
                        },
                        style = MaterialTheme.typography.bodyLarge, color = if (items.isEmpty()) ShokuColors.Muted else ShokuColors.Ink, maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Box {
                    Box(Modifier.clip(CircleShape).clickable { open = true }) { StatusChip("Change", ChipStyle.Tinted) }
                    androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, containerColor = ShokuColors.Paper) {
                        c.options.forEach { t ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text((if (t.id == c.current?.id) "✓ " else "") + t.name, color = ShokuColors.Ink) },
                                onClick = { open = false; onChoose(c.meal, t.id) },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Don't add today", color = ShokuColors.Muted) }, onClick = { open = false; onChoose(c.meal, null) })
                    }
                }
            }
        }
        if (p?.missingSlots?.isNotEmpty() == true) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No sabzi or dal batch in the fridge.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).clickable(onClick = onCook)) { StatusChip("Cook a batch", ChipStyle.Tinted) }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (entries.isNotEmpty()) app.shoku.ui.components.PrimaryButton("Add as planned · ${entries.size} items", onClick = onAdd)
        } }
    }
}
