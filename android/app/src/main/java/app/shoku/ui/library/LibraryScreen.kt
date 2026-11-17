package app.shoku.ui.library

import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import app.shoku.ui.plan.stockSection
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Batch
import app.shoku.data.db.Dish
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.domain.Library
import app.shoku.domain.fractionLeft
import app.shoku.domain.matchesAll
import app.shoku.domain.searchKey
import app.shoku.domain.searchText
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.MacroBar
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.Segmented
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SwitchRow
import app.shoku.ui.components.asField
import app.shoku.ui.components.toAmount
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.launch
import java.time.LocalDate


@Composable
fun LibraryScreen(vm: LibraryViewModel, snackbar: SnackbarHostState, contentPadding: PaddingValues, planVm: app.shoku.ui.plan.PlanViewModel) {
    val data by vm.data.collectAsState()
    val scope = rememberCoroutineScope()
    fun toast(t: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(t) }
    fun undoToast(t: String, undo: () -> Unit) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.showSnackbar(t, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) undo()
    }

    // The back stack lives in the view model, so system back returns to the previous screen
    // with its tab, search and scroll position intact, even after switching bottom tabs.
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    val d = data ?: return
    when (val r = vm.stack.last()) {
        LibRoute.List -> LibraryList(vm, planVm, d.library, d, contentPadding, onDish = { vm.open(LibRoute.DishDetail(it)) }, onBatch = { vm.open(LibRoute.BatchDetail(it)) }, toast = { toast(it) }, undoToast = { t, u -> undoToast(t, u) })
        is LibRoute.DishDetail -> DishScreen(
            vm, d, r.id, contentPadding,
            onBack = { vm.back() },
            onCook = { vm.open(LibRoute.Cook(r.id)) },
            onOpenBatch = { vm.open(LibRoute.BatchDetail(it)) },
            onOpenDish = { vm.open(LibRoute.DishDetail(it)) },
            toast = { toast(it) },
        )
        is LibRoute.BatchDetail -> BatchScreen(vm, d, batchId = r.id, cookFromDishId = null, contentPadding = contentPadding, onDone = { vm.back() }, toast = { toast(it) })
        // After saving a new batch, show it in place of the cook screen; back then returns to the dish.
        is LibRoute.Cook -> BatchScreen(
            vm, d, batchId = null, cookFromDishId = r.dishId, contentPadding = contentPadding,
            onDone = { id ->
                when {
                    // Cooking started from Add: go back there with the batch (or without, if left).
                    vm.afterCook != null -> { vm.back(); vm.finishCook(id) }
                    id != null -> vm.replaceTop(LibRoute.BatchDetail(id))
                    else -> vm.back()
                }
            },
            toast = { toast(it) },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LibraryList(
    vm: LibraryViewModel,
    planVm: app.shoku.ui.plan.PlanViewModel,
    library: Library,
    data: LibraryData,
    contentPadding: PaddingValues,
    onDish: (String) -> Unit,
    onBatch: (String) -> Unit,
    toast: (String) -> Unit,
    undoToast: (String, () -> Unit) -> Unit = { t, _ -> toast(t) },
) {
    val tab = vm.tab
    // The field shows typing at once; lists filter on a copy that follows after a short pause.
    val typed = vm.query
    val query by app.shoku.ui.components.rememberSearchResults(typed, typed) { it }
    var editingFood by remember { mutableStateOf<Food?>(null) }
    var creatingFood by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vm.editFoodRequest, library) {
        val id = vm.editFoodRequest ?: return@LaunchedEffect
        library.foods[id]?.let { editingFood = it; vm.editFoodRequest = null }
    }
    // Stock (what's at home), with its own sheets.
    val stockUi = remember { app.shoku.ui.plan.StockUi() }
    // Opened from a stock card: closing the food goes back to that card; opened from search: back to the search.
    var foodFromStock by remember { mutableStateOf<app.shoku.data.db.StockItem?>(null) }
    fun closeFood() {
        editingFood = null
        val card = foodFromStock
        foodFromStock = null
        if (card != null) stockUi.open = card else app.shoku.ui.search.SearchReturn.go()
    }
    val stock by planVm.stockView.collectAsState()
    val stockBusy by planVm.busy.collectAsState()
    val stockScan = app.shoku.ui.plan.rememberStockScan(planVm)
    // A bill: screenshots (a long Lidl receipt is fine) or a photo of a paper one.
    val shoku = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.shoku.ShokuApplication
    val billState by app.shoku.data.BillImport.state.collectAsState()
    val bills by app.shoku.data.BillImport.bills.collectAsState()
    val openBillReview by app.shoku.data.BillImport.openReview.collectAsState()
    var choosingBill by remember { mutableStateOf(false) }
    var reviewingBill by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(openBillReview) { if (openBillReview) { reviewingBill = true; app.shoku.data.BillImport.openReview.value = false } }
    val billGallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris -> app.shoku.data.BillImport.start(shoku, uris) }
    val billPhotoUri = remember {
        val dir = java.io.File(shoku.cacheDir, "images").apply { mkdirs() }
        androidx.core.content.FileProvider.getUriForFile(shoku, "${shoku.packageName}.files", java.io.File(dir, "bill.jpg"))
    }
    val billCamera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (ok) app.shoku.data.BillImport.start(shoku, listOf(billPhotoUri))
    }
    androidx.compose.runtime.LaunchedEffect(stockUi.editFood, library) {
        val item = stockUi.editFood ?: return@LaunchedEffect
        library.foods[item.foodId]?.let { editingFood = it; foodFromStock = item }
        stockUi.editFood = null
    }
    androidx.compose.runtime.LaunchedEffect(planVm.openStockRequest, stock) {
        val id = planVm.openStockRequest ?: return@LaunchedEffect
        stock.levels.firstOrNull { it.item.id == id }?.let { stockUi.open = it.item; planVm.openStockRequest = null }
    }
    var creatingDish by remember { mutableStateOf(false) }
    var cookingNewDish by remember { mutableStateOf(false) }
    // Finished batches and archived dishes stay closed until opened (kept while the app runs).
    var showFinished by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showArchived by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var newDishName by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(vm.newDishRequest) {
        vm.newDishRequest?.let { newDishName = it; cookingNewDish = true; creatingDish = true; vm.newDishRequest = null }
    }
    // Foods from a scan, a label photo or typed by hand, straight into the library.
    val capture = rememberFoodCapture { food -> toast("Added ${food.name} to your library") }

    fun matches(name: String) = matchesAll(searchKey(name), query)

    LazyColumn(
        Modifier.fillMaxSize(),
        state = vm.listState,
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            ScreenTitle(
                "Library",
                actions = {
                    when (tab) {
                        LibTab.Foods -> androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            SquareIconButton(androidx.compose.material.icons.Icons.Outlined.QrCodeScanner, "Scan a barcode", onClick = capture.scan)
                            SquareIconButton(androidx.compose.material.icons.Icons.Outlined.PhotoCamera, "Photo of a pack", onClick = capture.pack)
                            SquareIconButton(Icons.Rounded.Add, "New food", onClick = { creatingFood = true })
                        }
                        LibTab.Dishes -> SquareIconButton(Icons.Rounded.Add, "New dish", onClick = { creatingDish = true })
                        LibTab.Batches -> SquareIconButton(Icons.Rounded.Add, "Cook a batch", onClick = { vm.pickingCook = true })
                        LibTab.Stock -> SquareIconButton(Icons.Rounded.Add, "Add to stock", onClick = { stockUi.searching = true })
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            Segmented(
                listOf("Foods", "Dishes", "Batches", "Stock"),
                tab.ordinal,
            ) { vm.tab = LibTab.entries[it] }
            Spacer(Modifier.height(10.dp))
            LibrarySearch(typed, placeholder = if (tab == LibTab.Stock) "Search what's at home" else "Search") { vm.query = it }
        }

        when (tab) {
            LibTab.Foods -> {
                // Foods the AI made up from estimates (recipes): worth one look each.
                val toCheck = library.foods.values.filter { !it.archived && !it.deleted && it.source == "ai-estimate" }.sortedBy { it.name.lowercase() }
                if (toCheck.isNotEmpty() && query.isBlank()) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text("Check ${toCheck.size} ${if (toCheck.size == 1) "food" else "foods"} the AI added", style = MaterialTheme.typography.titleMedium)
                            Text("Made from estimates when a recipe named something new. Fix the numbers if a label says otherwise, then save to mark it checked.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        }
                    }
                    item { ListCard(toCheck, key = { "check-" + it.id }) { f -> FoodRow(library, f, data.usageCount("food", f.id)) { editingFood = f } } }
                }
                // Generics first, then products, each alphabetically.
                val foods = library.foods.values.filter { !it.archived && matchesAll(library.searchText(it), query) }
                    .sortedWith(compareBy<Food>({ it.kind != FoodKind.GENERIC }, { it.name.lowercase() }))
                // Until "See all": the 20 most-logged. Searching shows every match.
                val open = vm.showAllFoods || query.isNotBlank()
                val shownFoods = if (open) foods else foods.sortedByDescending { data.usageCount("food", it.id) }.take(20)
                if (shownFoods.isNotEmpty()) item { ListCard(shownFoods, key = { it.id }) { f -> FoodRow(library, f, data.usageCount("food", f.id)) { editingFood = f } } }
                if (query.isBlank() && foods.size > 20) item(key = "foods-more") {
                    app.shoku.ui.components.SeeMoreChip(foods.size, 20, vm.showAllFoods, { vm.showAllFoods = !vm.showAllFoods }, what = "foods")
                }
                val archivedFoods = library.foods.values.filter { it.archived && !it.deleted && !app.shoku.domain.FoodFamilies.isHiddenSizeRow(it) && matchesAll(library.searchText(it), query) }.sortedBy { it.name.lowercase() }
                if (archivedFoods.isNotEmpty()) {
                    val openArch = vm.showArchivedFoods || query.isNotBlank()
                    item(key = "foods-archived-head") { CollapsibleHeader("Archived foods", archivedFoods.size, openArch) { vm.showArchivedFoods = !vm.showArchivedFoods } }
                    if (openArch) item(key = "foods-archived") { ListCard(archivedFoods, key = { "a-" + it.id }) { f -> FoodRow(library, f, data.usageCount("food", f.id)) { editingFood = f } } }
                }
                // Not in the library yet: the Indian and German databases, then Open Food Facts.
                if (query.isNotBlank()) {
                    if (foods.isEmpty()) item { Text("Nothing in your library matches. Add it from below.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                    val refs = vm.reference?.search(query, limit = 8)?.filter { !library.hasLive(it.libraryId) }.orEmpty()
                    if (refs.isNotEmpty()) {
                        item { SectionLabel("Add from food databases", trailing = "typical values") }
                        item {
                            ListCard(refs, key = { "ref-" + it.code }) { r ->
                                AddableRow(r.name, null, "${if (r.source == "indb") "Indian dish" else "German food database"} · ${Math.round(r.per100.kcal ?: 0.0)} kcal/100 g") {
                                    vm.addReference(r) { food -> toast("Added ${food.name} to your library"); editingFood = food }
                                }
                            }
                        }
                    }
                    val known = library.foods.values.mapNotNull { it.barcode }.toSet()
                    val online = vm.online.filter { it.barcode !in known }
                    if (online.isNotEmpty() || vm.onlineLoading) item { SectionLabel("Packaged products", trailing = if (vm.onlineLoading) "searching…" else "Open Food Facts") }
                    if (online.isNotEmpty()) {
                        item {
                            ListCard(online, key = { "off-" + it.barcode }) { p ->
                                AddableRow(p.cleanName(), p.brand, "per 100 ${p.unit} · ${p.kcal?.let { Math.round(it) } ?: "?"} kcal · ${p.protein?.let { app.shoku.ui.formatQty(Math.round(it * 10) / 10.0) } ?: "?"} g protein") {
                                    vm.addOnline(p) { food -> toast("Added ${food.name} to your library"); editingFood = food }
                                }
                            }
                        }
                    }
                }
            }
            LibTab.Dishes -> {
                val dishes = library.dishes.values.filter { !it.archived && matches(it.name) }.sortedBy { it.name.lowercase() }
                item { ListCard(dishes, key = { it.id }) { dish -> DishRow(library, dish) { onDish(dish.id) } } }
                val archived = library.dishes.values.filter { it.archived && matches(it.name) }
                if (archived.isNotEmpty()) {
                    val open = showArchived || query.isNotBlank()
                    item(key = "archived-head") { CollapsibleHeader("Archived", archived.size, open) { showArchived = !showArchived } }
                    if (open) item { ListCard(archived.sortedBy { it.name }, key = { it.id }) { dish -> DishRow(library, dish) { onDish(dish.id) } } }
                }
            }
            LibTab.Stock -> {
                stockSection(
                    stock, stockBusy,
                    onScan = stockScan, onSearch = { stockUi.searching = true }, onSay = { stockUi.saying = true },
                    onBill = { choosingBill = true },
                    billCard = run {
                        val toCheck = bills.sumOf { it.toReview.size }
                        val st = billState
                        when {
                            st is app.shoku.data.BillImport.State.Working -> { { BillCard(if (st.step.startsWith("Reading")) "Reading your bill…" else st.step, "You can leave this screen; a notification says when it's done.", null) } }
                            st is app.shoku.data.BillImport.State.Failed -> { { BillCard("Couldn't add the bill", st.message, "OK") { app.shoku.data.BillImport.dismissFailure() } } }
                            toCheck > 0 -> { { BillCard("Check $toCheck new ${if (toCheck == 1) "item" else "items"} from your bill", "Already in stock. Keep, edit, replace or remove them.", "Review") { reviewingBill = true } } }
                            else -> null
                        }
                    },
                    onBought = planVm::bought, onOpen = { stockUi.open = it }, onStatus = planVm::setStatus,
                    onDismiss = planVm::dismiss, onShowHidden = planVm::showHidden,
                    onBoughtWeek = { w -> planVm.boughtWeek(w) },
                    onDismissWeek = { w -> stock.weekFrom?.let { from -> planVm.dismissWeek(w, from) } },
                    open = planVm.openLists, onToggle = planVm::toggleList, query = query,
                )
            }
            LibTab.Batches -> {
                // In the fridge first; finished and used-up batches in their own list, closed until opened.
                val all = library.batches.values.filter { matches(it.dishName) }.sortedWith(compareByDescending<Batch> { it.date }.thenBy { it.dishName })
                val (done, active) = all.partition { b -> b.finished || data.consumption(b).let { (m, c) -> c.fractionLeft(m) < 0.02 } }
                item(key = "fridge-head") { SectionLabel("In the fridge", trailing = "${active.size}") }
                if (active.isNotEmpty()) item { ListCard(active, key = { it.id }) { b -> BatchRow(data, b) { onBatch(b.id) } } }
                else item { Text(if (query.isBlank()) "Nothing in the fridge. Cook a batch with +." else "No batch in the fridge matches.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                if (done.isNotEmpty()) {
                    val open = showFinished || query.isNotBlank()
                    item(key = "finished-head") { CollapsibleHeader("Finished", done.size, open) { showFinished = !showFinished } }
                    if (open) item(key = "finished") { ListCard(done, key = { it.id }) { b -> BatchRow(data, b) { onBatch(b.id) } } }
                }
            }
        }
    }

    editingFood?.let { f ->
        FoodEditor(
            food = f,
            usedIn = data.usageCount("food", f.id),
            familyChoices = familyChoicesFor(library, f.name, f.parentId),
            portions = library.portions[f.id].orEmpty(),
            inherited = library.portionsOf(f).filter { it.foodId != f.id },
            inheritedFrom = f.parentId?.let { library.foods[it]?.name },
            onSavePortions = { vm.savePortions(it) },
            onSave = { edited, updatePast ->
                // Saving an AI-made food counts as checking it.
                val food = if (edited.source == "ai-estimate") edited.copy(source = "ai-checked") else edited
                vm.saveFood(food, updatePast) { n ->
                    when {
                        food.id != f.id -> toast("Added ${food.brand}'s ${food.name} to your library")
                        updatePast && n > 0 -> toast("Saved. $n past entries updated")
                        else -> toast("Saved")
                    }
                }
                closeFood()
            },
            onArchive = { vm.archiveFood(f); closeFood(); undoToast("${f.name} archived") { vm.unarchiveFood(f) } },
            onUnarchive = { vm.unarchiveFood(f); closeFood(); toast("${f.name} is back") },
            onDelete = run {
                // Only a food nothing uses: never logged, in no dish, batch, meal plan or stock, no family under it.
                val plan = planVm.plan.value
                val unused = data.usageCount("food", f.id) == 0 &&
                    library.dishIngredients.values.none { l -> l.any { it.refType == "food" && it.refId == f.id && !it.deleted } } &&
                    library.batchIngredients.values.none { l -> l.any { it.refType == "food" && it.refId == f.id && !it.deleted } } &&
                    plan.items.none { it.refType == "food" && it.refId == f.id && !it.deleted } &&
                    stock.levels.none { it.item.foodId == f.id } &&
                    library.foods.values.none { it.parentId == f.id && !it.deleted }
                if (unused) ({ vm.deleteFood(f); closeFood(); toast("${f.name} deleted") }) else null
            },
            onDismiss = { closeFood() },
        )
    }
    if (creatingFood) {
        FoodEditor(
            food = null, usedIn = 0,
            onSave = { food, _ -> vm.saveFood(food, false); creatingFood = false; toast("Added ${food.name}") },
            onArchive = null,
            onDismiss = { creatingFood = false },
        )
    }
    app.shoku.ui.plan.StockSheets(planVm, stockUi)
    if (choosingBill) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { choosingBill = false }, containerColor = app.shoku.ui.theme.ShokuColors.Paper, shape = app.shoku.ui.theme.ShokuShapes.Sheet) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a bill to stock", style = MaterialTheme.typography.headlineSmall)
                Text("Your foods get their stock straight away; new things are added too, for you to check. Tip: share the receipt from the Lidl app to Shoku.",
                    style = MaterialTheme.typography.bodyMedium, color = app.shoku.ui.theme.ShokuColors.Muted)
                app.shoku.ui.components.PrimaryButton("Screenshots from the gallery", onClick = {
                    choosingBill = false
                    billGallery.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                app.shoku.ui.components.SecondaryButton("Photo of a paper bill", onClick = { choosingBill = false; billCamera.launch(billPhotoUri) })
            }
        }
    }
    if (reviewingBill) {
        val reviewScope = androidx.compose.runtime.rememberCoroutineScope()
        app.shoku.ui.plan.BillReviewSheet(
            bills = bills,
            onEdit = { l -> reviewingBill = false; stock.levels.firstOrNull { it.item.foodId == l.foodId }?.let { stockUi.billLine = l; stockUi.open = it.item } },
            onReplace = { l -> reviewingBill = false; planVm.replacingBill = l; stockUi.searching = true },
            onRemove = { lines -> reviewScope.launch { lines.forEach { app.shoku.data.BillImport.remove(shoku, it) } } },
            // Not food by the AI's reading, but it is: find it (or make it New), starting from the line.
            onAddAnyway = { text -> reviewingBill = false; app.shoku.data.BillImport.dropSkipped(text); stockUi.searchText = text; stockUi.searching = true },
            onDismiss = { reviewingBill = false },
        )
    }
    if (vm.pickingCook) {
        CookPicker(
            library = library,
            onPick = { dishId -> vm.pickingCook = false; vm.open(LibRoute.Cook(dishId)) },
            onNewDish = { name -> vm.pickingCook = false; newDishName = name; cookingNewDish = true; creatingDish = true },
            onDismiss = { vm.pickingCook = false; vm.finishCook(null) },
        )
    }
    if (creatingDish) {
        NewDishSheet(
            initialName = newDishName, cook = cookingNewDish,
            onCreate = { name, servings ->
                val dish = Dish(id = ShokuRepository.newId(), name = name, fixed = false, servings = servings)
                // From "Cook a batch": straight to the batch; what goes in becomes the dish's recipe on save.
                val cook = cookingNewDish
                vm.saveDish(dish, emptyList()) { if (cook) vm.open(LibRoute.Cook(dish.id)) else onDish(dish.id) }
                creatingDish = false; cookingNewDish = false; newDishName = ""
            },
            onDismiss = { if (cookingNewDish) vm.finishCook(null); creatingDish = false; cookingNewDish = false; newDishName = "" },
        )
    }
}

@Composable
fun LibrarySearch(value: String, placeholder: String = "Search", onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(ShokuShapes.Button).background(ShokuColors.Tint).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = ShokuColors.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ShokuColors.Ink),
                cursorBrush = SolidColor(ShokuColors.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = ShokuColors.Muted, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onChange("") })
    }
}

@Composable
fun <T> ListCard(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        Text("Nothing here.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
        return
    }
    OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
        items.forEachIndexed { i, item ->
            androidx.compose.runtime.key(key(item)) {
                if (i > 0) Hairline()
                row(item)
            }
        }
    }
}

@Composable
private fun FoodRow(library: Library, f: Food, uses: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            app.shoku.ui.components.NameWithTag(f.name, if (f.kind == FoodKind.GENERIC) "Any brand" else f.brand, MaterialTheme.typography.titleMedium)
            val prefix = when {
                f.kind == FoodKind.GENERIC -> ""
                f.parentId != null -> "${library.foods[f.parentId]?.name ?: ""} · "
                else -> ""
            }
            val portions = library.portions[f.id].orEmpty().joinToString(", ") { it.label }.let { if (it.isEmpty()) "" else " · $it" }
            Text(
                prefix + "per ${formatUnitQty(f.baseQty, f.unit)} · ${f.protein?.let { formatQty(it) } ?: "–"} g protein$portions" + if (uses > 0) " · logged $uses×" else "",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(f.kcal?.let { "${formatQty(Math.round(it).toDouble())} kcal" } ?: "–", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DishRow(library: Library, dish: Dish, onClick: () -> Unit) {
    val m = library.dishMetrics(dish)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(dish.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ings = library.dishIngredients[dish.id].orEmpty().size
            Text(
                if (dish.fixed) "Fixed macros" else "$ings ingredients · makes ${formatUnitQty(dish.servings, "serving")}",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(m.perServing.kcal?.let { "${formatQty(Math.round(it).toDouble())} kcal" } ?: "–", style = MaterialTheme.typography.labelLarge)
            Text("per serving", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        }
    }
}

/** A section title that opens and closes its list: "Finished · 24" with a chevron. */
@Composable
private fun CollapsibleHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable(onClick = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(CircleShape)) { StatusChip("$count", ChipStyle.Tinted) }
        Spacer(Modifier.weight(1f))
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, app.shoku.ui.theme.ShokuMotion.spatial(), label = "chevron")
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            if (open) "Hide $title" else "Show $title", tint = ShokuColors.Muted,
            modifier = Modifier.graphicsLayer { rotationZ = turn },
        )
    }
}

@Composable
private fun BatchRow(data: LibraryData, b: Batch, onClick: () -> Unit) {
    val (m, c) = data.consumption(b)
    val fraction = c.fractionLeft(m)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.dishName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val left = c.remainingWeight?.let { "${formatQty(it)} ${m.finalUnit} left" } ?: "${formatUnitQty(c.remainingServings, "serving")} left"
                Text("${runCatching { LocalDate.parse(b.date).dayMonth() }.getOrDefault(b.date)} · $left", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            when {
                b.finished -> StatusChip("Finished", ChipStyle.Tinted)
                fraction < 0.02 -> StatusChip("Used up", ChipStyle.Tinted)
                else -> StatusChip("Active", ChipStyle.Outlined)
            }
        }
        if (!b.finished && fraction >= 0.02) {
            Spacer(Modifier.height(8.dp))
            MacroBar(fraction.toFloat(), track = ShokuColors.Tint, height = 4.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewDishSheet(onCreate: (String, Double) -> Unit, onDismiss: () -> Unit, initialName: String = "", cook: Boolean = false) {
    var name by remember { mutableStateOf(initialName) }
    var servings by remember { mutableStateOf("1") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New dish", style = MaterialTheme.typography.headlineSmall)
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Rajma Tadka")
            LabeledField("Makes", servings, { servings = it }, Modifier.fillMaxWidth(), numeric = true, suffix = "servings")
            Text(
                if (cook) "Next: what went into the pot. Saving the batch saves it as this dish's recipe, so next time it's one tap."
                else "Add ingredients on the next screen.",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
            PrimaryButton(if (name.isBlank()) "Name it first" else if (cook) "Start cooking" else "Create dish", onClick = { val s = servings.toAmount() ?: 1.0; if (name.isNotBlank()) onCreate(name.trim(), if (s > 0) s else 1.0) })
        }
    }
}

/** A food that isn't in the library yet, with an Add button. */
@Composable
private fun AddableRow(name: String, tag: String?, subtitle: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            app.shoku.ui.components.NameWithTag(name, tag, MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clip(CircleShape).clickable(onClick = onAdd)) { app.shoku.ui.components.StatusChip("Add", app.shoku.ui.components.ChipStyle.Filled) }
    }
}

/** "What are you cooking?": dishes, most recently cooked first, with search; or a new dish. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CookPicker(library: Library, onPick: (String) -> Unit, onNewDish: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val lastCooked = library.batches.values.filter { !it.deleted && it.dishId != null }.groupBy { it.dishId!! }.mapValues { (_, b) -> b.maxOf { it.date } }
    val dishes = library.dishes.values.filter { !it.archived && !it.deleted && matchesAll(searchKey(it.name), q) }
        .sortedWith(compareByDescending<Dish> { lastCooked[it.id] ?: "" }.thenBy { it.name.lowercase() })
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text("Cook a batch", style = MaterialTheme.typography.headlineSmall)
            Text("Which dish? Next you set what went in, servings and the cooked weight.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            Spacer(Modifier.height(10.dp))
            LibrarySearch(q) { q = it }
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text(if (q.isBlank()) "+ New dish" else "+ New dish \"${q.trim()}\"", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onNewDish(q.trim()) }.padding(vertical = 12.dp))
                    Hairline()
                }
                items(dishes.size) { i ->
                    val d = dishes[i]
                    Column(Modifier.fillMaxWidth().clickable { onPick(d.id) }.padding(vertical = 10.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(lastCooked[d.id]?.let { "Last cooked ${java.time.LocalDate.parse(it).let { day -> day.dayMonth() }}" } ?: "Not cooked yet",
                            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    }
                }
            }
        }
    }
}

/** The card above Stock about a bill: reading it, or new items to check. */
@Composable
private fun BillCard(title: String, text: String, action: String?, onAction: () -> Unit = {}) {
    app.shoku.ui.components.OutlinedCard(Modifier.fillMaxWidth(), onClick = if (action != null) onAction else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = app.shoku.ui.theme.ShokuColors.Muted)
            }
            if (action != null) {
                Spacer(Modifier.width(10.dp))
                app.shoku.ui.components.StatusChip(action, app.shoku.ui.components.ChipStyle.Filled)
            }
        }
    }
}
