package app.shoku.ui.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Meals
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.shown
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.QuantitySheet
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.Segmented
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.rememberPhotoCapture
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.relativeTitle
import app.shoku.ui.shortLabel
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.launch
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun AddScreen(
    vm: AddViewModel,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    startDescribing: Boolean = false,
    startBarcode: String? = null,
    startPlate: Boolean = false,
    startScanner: Boolean = false,
    /** Cook a batch (of a new dish named like the search, or picked); Add comes back after. */
    onCook: (name: String?) -> Unit = {},
    /** A batch just cooked from here: opens its amount sheet once it shows in the fridge. */
    startBatchId: String? = null,
    /** Search text to start with (from global search). */
    startQuery: String? = null,
    /** "Add to stock instead": bought something rather than ate it. */
    onAddToStock: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { startQuery?.takeIf { it.isNotBlank() }?.let { vm.query.value = it } }
    LaunchedEffect(Unit) { startBarcode?.let { vm.onBarcode(it) } }
    val state by vm.state.collectAsState()
    val meal by vm.meal.collectAsState()
    val query by vm.query.collectAsState()
    val added by vm.added.collectAsState()
    val scan by vm.scan.collectAsState()
    val aiState by vm.ai.collectAsState()
    val online by vm.online.collectAsState()
    var describing by remember { mutableStateOf(startDescribing) }
    var labelBarcode by remember { mutableStateOf<String?>(null) }
    val captureLabel = rememberPhotoCapture { photo -> vm.aiLabel(photo, labelBarcode) }
    var packing by remember { mutableStateOf(false) }
    var plating by remember { mutableStateOf(startPlate) }
    val context = LocalContext.current
    val addedCount = added.sumOf { it.size }
    var sheetFor by remember { mutableStateOf<Suggestion?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val defaultStatus = ShokuRepository.defaultStatusFor(vm.date)

    BackHandler(onBack = onClose)

    // Google's code scanner: runs in Play services, needs no camera permission in this app.
    fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let { vm.onBarcode(it) } }
            .addOnFailureListener { e -> scope.launch { snackbar.showSnackbar("Scanner unavailable: ${e.message}") } }
    }
    LaunchedEffect(Unit) { if (startScanner) startScan() }

    // A scanned food that is ready opens the amount sheet straight away.
    LaunchedEffect(scan) {
        when (val st = scan) {
            is ScanState.Ready -> { sheetFor = st.suggestion; vm.clearScan() }
            is ScanState.Failed -> { snackbar.showSnackbar(st.message); vm.clearScan() }
            else -> {}
        }
    }
    var pendingBatch by remember { mutableStateOf(startBatchId) }
    var showAllUsual by remember(meal) { mutableStateOf(false) }
    var showAllResults by remember(query) { mutableStateOf(false) }
    var showAllRefs by remember(query) { mutableStateOf(false) }
    var showOlder by remember(meal) { mutableStateOf(false) }
    LaunchedEffect(state.fridge, pendingBatch) {
        val id = pendingBatch ?: return@LaunchedEffect
        state.fridge.firstOrNull { it.refId == id }?.let { sheetFor = it; pendingBatch = null }
    }
    LaunchedEffect(aiState) {
        (aiState as? AiState.Failed)?.let { snackbar.showSnackbar(it.message); vm.clearAi() }
    }

    fun announce(text: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) vm.undoLast()
    }

    // "Did you mean yours?": a database or online pick that you already have (and log) under your own name.
    var didYouMean by remember { mutableStateOf<Triple<Suggestion, Pair<Suggestion, Int>, Boolean>?>(null) }
    fun quickAdd(s: Suggestion) { vm.add(s, s.unit, s.qty, defaultStatus); announce("Added ${s.name}") }
    fun checkOwn(s: Suggestion, quick: Boolean) {
        val own = vm.ownVersion(s)
        when {
            own != null -> didYouMean = Triple(s, own, quick)
            quick -> quickAdd(s)
            else -> sheetFor = s
        }
    }
    didYouMean?.let { (picked, own, quick) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { didYouMean = null },
            containerColor = ShokuColors.Paper,
            title = { Text("Did you mean yours?") },
            text = {
                Text(
                    "You picked ${picked.name}${if (picked.reference != null) " from the food databases" else " (online)"}. " +
                        "You have ${own.first.name}" + (if (own.second > 0) ", logged ${own.second}× before." else " in your library.") + " Log yours instead?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { didYouMean = null; if (quick) quickAdd(own.first) else sheetFor = own.first }) { Text("Use mine", color = ShokuColors.Ink) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { didYouMean = null; if (quick) quickAdd(picked) else sheetFor = picked }) { Text("No, this one", color = ShokuColors.Muted) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ShokuColors.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header
        Column(Modifier.padding(horizontal = ShokuSpacing.Screen).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onClose)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add to $meal", style = MaterialTheme.typography.headlineMedium)
                    Text("${vm.date.relativeTitle()} · ${vm.date.shortLabel()}", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                    // Bought it rather than ate it? The same + button adds to stock.
                    if (onAddToStock != null) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onAddToStock)) {
                            app.shoku.ui.components.StatusChip("+ Add to stock instead", app.shoku.ui.components.ChipStyle.Tinted)
                        }
                    }
                }
                SquareIconButton(Icons.Outlined.QrCodeScanner, "Scan a barcode", onClick = ::startScan)
            }
            Spacer(Modifier.height(14.dp))
            val mealSettings by app.shoku.data.MealSettingsStore.current.collectAsState()
            val meals = Meals.ALL.filter { !mealSettings.hiddenOn(it, vm.date) || it == meal }
            Segmented(meals, meals.indexOf(meal).coerceAtLeast(0)) { vm.meal.value = meals[it] }
            Spacer(Modifier.height(12.dp))
            SearchField(query, onChange = { vm.query.value = it })
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = ShokuSpacing.Screen, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
        ) {
            (scan as? ScanState.Looking)?.let { st ->
                item(key = "scan") {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text("Looking up ${st.barcode}…", style = MaterialTheme.typography.titleMedium)
                        Text("Checking your foods, then Open Food Facts", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    }
                }
            }
            if (query.isBlank()) {
                item(key = "ai-tiles") {
                    AiTiles(
                        onDescribe = { describing = true },
                        onLabel = { labelBarcode = null; captureLabel() },
                        onPlate = { plating = true },
                        onPack = { labelBarcode = null; packing = true },
                    )
                }
                item(key = "make-tiles") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MakeTile("New food", "Type it in", Modifier.weight(1f)) { vm.newFood("") }
                        MakeTile("Cook a batch", "New or saved dish", Modifier.weight(1f)) { onCook(null) }
                    }
                }
                state.repeat?.let { option ->
                    item(key = "repeat") {
                        OutlinedCard(Modifier.fillMaxWidth(), onClick = {
                            vm.copy(option, defaultStatus)
                            announce("Copied ${option.entries.size} items")
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(ShokuShapes.Tile).background(ShokuColors.Tint), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ContentCopy, null, tint = ShokuColors.Ink, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Copy ${vm.repeatLabel(option).replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        option.entries.joinToString(", ") { e ->
                                            (if (e.refType == app.shoku.data.db.RefType.FOOD) e.refId?.let { state.library?.foods?.get(it)?.name } else null) ?: e.name
                                        },
                                        style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${formatQty(Math.round(option.kcal).toDouble())} kcal", style = NumberStyle.Row)
                                    Text("${option.entries.size} items", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                                }
                            }
                        }
                    }
                }
                if (state.olderRepeats.isNotEmpty()) {
                    item(key = "older-repeats-chip") {
                        app.shoku.ui.components.SeeMoreChip(state.olderRepeats.size + 1, 1, showOlder, { showOlder = !showOlder }, what = "earlier ${meal.lowercase().let { if (it.endsWith("s")) it else "${it}s" }}")
                    }
                    if (showOlder) item(key = "older-repeats") {
                        OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                            state.olderRepeats.forEachIndexed { k, option ->
                                if (k > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.copy(option, defaultStatus); announce("Copied ${option.entries.size} items") }.padding(vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Copy ${vm.repeatLabel(option).replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.titleMedium)
                                        Text(option.entries.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text("${formatQty(Math.round(option.kcal).toDouble())} kcal", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                if (state.fridge.isNotEmpty()) {
                    item(key = "fridge-label") { SectionLabel("In the fridge") }
                    item(key = "fridge") { SuggestionCard(state.fridge, onOpen = { sheetFor = it }, onQuickAdd = { s -> vm.add(s, s.unit, s.qty, defaultStatus); announce("Added ${s.name}") }) }
                }
                if (state.usual.isNotEmpty()) {
                    // The two most likely; the rest behind "See all" (any meal).
                    val shown = if (showAllUsual) state.usual else state.usual.take(2)
                    item(key = "usual-label") { SectionLabel("Usual for ${meal.lowercase()}", trailing = "tap + to add") }
                    item(key = "usual") { SuggestionCard(shown, onOpen = { sheetFor = it }, onQuickAdd = { s -> vm.add(s, s.unit, s.qty, defaultStatus); announce("Added ${s.name}") }) }
                    item(key = "usual-more") { app.shoku.ui.components.SeeMoreChip(state.usual.size, 2, showAllUsual, { showAllUsual = !showAllUsual }) }
                }
                if (state.library?.foods?.isEmpty() == true) {
                    item { Text("Your library is empty. Search above, scan a barcode, or tap New food to start it.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                }
            } else {
                item(key = "ask-ai") {
                    AskAiRow(query, several = state.parsed?.multiple == true, onClick = { focus.clearFocus(); vm.aiDescribe(query.trim()) })
                }
                if (state.results.isEmpty() && state.references.isEmpty() && online.items.isEmpty() && !online.loading) {
                    item { Text("Nothing matches \"$query\" in your foods. Ask AI above, or scan the barcode.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                }
                if (state.results.isNotEmpty()) {
                    item(key = "results") { SuggestionCard(state.results.shown(showAllResults, 8), onOpen = { focus.clearFocus(); sheetFor = it }, onQuickAdd = { s -> vm.add(s, s.unit, s.qty, defaultStatus); announce("Added ${s.name}") }) }
                }
                if (state.results.size > 8) item(key = "results-more") { app.shoku.ui.components.SeeMoreChip(state.results.size, 8, showAllResults, { showAllResults = !showAllResults }) }
                if (state.references.isNotEmpty()) {
                    item(key = "ref-label") { SectionLabel("From food databases", trailing = "typical values") }
                    item(key = "refs") { SuggestionCard(state.references.shown(showAllRefs, 4), onOpen = { focus.clearFocus(); checkOwn(it, false) }, onQuickAdd = { s -> checkOwn(s, true) }) }
                    if (state.references.size > 4) item(key = "refs-more") { app.shoku.ui.components.SeeMoreChip(state.references.size, 4, showAllRefs, { showAllRefs = !showAllRefs }) }
                }
                if (online.failed && online.items.isEmpty()) item(key = "online-failed") {
                    Text("Online search is unavailable right now (no connection?). Your foods and the databases still work.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                }
                if (online.items.isNotEmpty() || online.loading) {
                    item(key = "online-label") {
                        SectionLabel("Packaged products", trailing = if (online.loading) "searching…" else "Open Food Facts")
                    }
                }
                if (online.items.isNotEmpty()) {
                    item(key = "online") { SuggestionCard(online.items, onOpen = { focus.clearFocus(); checkOwn(it, false) }, onQuickAdd = { s -> checkOwn(s, true) }) }
                }
                // Not there at all: make it.
                item(key = "make") {
                    OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                        MakeRow("+ New food “${query.trim()}”", "Type the values, or read them from the label") { focus.clearFocus(); vm.newFood(query.trim()) }
                        Hairline()
                        MakeRow("+ Cook “${query.trim()}” as a new dish", "Make the batch now, then log it here") { focus.clearFocus(); onCook(query.trim()) }
                        Hairline()
                        MakeRow("Scan its barcode", "Your foods, then Open Food Facts") { focus.clearFocus(); startScan() }
                    }
                }
            }
        }

        if (addedCount > 0) {
            Column(Modifier.padding(horizontal = ShokuSpacing.Screen, vertical = 10.dp)) {
                PrimaryButton("Done · $addedCount added", onClick = onClose)
            }
        }
    }

    ScanEditor(vm, scan, onReadLabel = { barcode -> labelBarcode = barcode; captureLabel() }, onPackPhoto = { barcode -> labelBarcode = barcode; packing = true })
    if (packing) PackSheet(onIdentify = { photos -> packing = false; vm.aiPack(photos, labelBarcode) }, onDismiss = { packing = false })
    (aiState as? AiState.Working)?.let { st -> app.shoku.ui.components.BusyOverlay(st.what, onCancel = vm::cancelAi) }

    if (describing) {
        DescribeSheet(onSubmit = { text -> describing = false; vm.aiDescribe(text) }, onDismiss = { describing = false })
    }
    if (plating) {
        PlateSheet(onEstimate = { photos, note -> plating = false; vm.aiPlate(photos, note) }, onDismiss = { plating = false })
    }
    (aiState as? AiState.Review)?.let { review ->
        AiReviewSheet(
            title = review.title,
            initial = review.items,
            library = state.library,
            onLog = { items -> vm.logAi(items, defaultStatus); announce("Logged ${items.size} ${if (items.size == 1) "item" else "items"}") },
            onDraft = { items -> vm.logAi(items, app.shoku.data.db.EntryStatus.PLANNED); announce("Saved ${items.size} as a draft on Today") },
            asPlanned = defaultStatus == app.shoku.data.db.EntryStatus.PLANNED,
            onDismiss = { vm.clearAi() },
        )
    }

    sheetFor?.let { s ->
        val library = state.library
        QuantitySheet(
            title = s.name,
            subtitle = listOfNotNull(s.tag, s.subtitle).joinToString(" · "),
            units = s.units(library),
            initialUnit = s.unit,
            initialQty = s.qty,
            initialStatus = defaultStatus,
            nutrientsFor = { unit, qty -> s.nutrients(library, unit, qty) },
            confirmLabel = "Add",
            askVariant = s.generic,
            askNote = true,
            onConfirm = { r ->
                vm.add(s, r.unit, r.qty, r.status, r.variant, r.note)
                sheetFor = null
                announce("Added ${s.name}")
            },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun ScanEditor(vm: AddViewModel, scan: ScanState, onReadLabel: (String?) -> Unit, onPackPhoto: (String?) -> Unit) {
    val st = scan as? ScanState.NewFood ?: return
    app.shoku.ui.library.NewFoodEditor(
        st.draft,
        onReadLabel = { b -> vm.clearScan(); onReadLabel(b) },
        onSave = { food, portions -> vm.saveScanned(food, portions) },
        onDismiss = { vm.clearScan() },
        onPackPhoto = { b -> vm.clearScan(); onPackPhoto(b) },
        onUseExisting = { f -> vm.useExisting(f, st.draft) },
    )
}

@Composable
private fun MakeTile(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier, onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
    }
}

@Composable
private fun MakeRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
    }
}

/** One tap hands the typed text to the AI, which matches your own foods first. */
@Composable
private fun AskAiRow(query: String, several: Boolean, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(ShokuShapes.Tile).background(if (several) ShokuColors.Fill else ShokuColors.Tint), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = if (several) ShokuColors.Paper else ShokuColors.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Log “${query.trim()}” with AI", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (several) "Several items: AI splits them and matches your foods" else "Matches your own foods first · about 1¢",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ShokuShapes.Button)
            .background(ShokuColors.Tint)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = ShokuColors.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search foods, dishes, batches", style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ShokuColors.Ink),
                cursorBrush = SolidColor(ShokuColors.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Rounded.Close, "Clear", tint = ShokuColors.Muted, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}

@Composable
private fun SuggestionCard(items: List<Suggestion>, onOpen: (Suggestion) -> Unit, onQuickAdd: (Suggestion) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        items.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(s) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    app.shoku.ui.components.NameWithTag(s.name, s.tag, MaterialTheme.typography.titleMedium)
                    Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatUnitQty(s.qty, s.unit), style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink)
                    Text(s.nutrients?.kcal?.let { "${Math.round(it)} kcal" } ?: "–", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(ShokuColors.Fill).clickable { onQuickAdd(s) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Add ${s.name}", tint = ShokuColors.Paper, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
