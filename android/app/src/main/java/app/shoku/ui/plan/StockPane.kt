package app.shoku.ui.plan

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.shoku.ShokuApplication
import app.shoku.domain.inFoodUnit
import kotlinx.coroutines.launch
import app.shoku.data.db.StockItem

/** Which stock sheet is open (search to add, say it, edit one item). */
class StockUi {
    var searching by mutableStateOf(false)
    var saying by mutableStateOf(false)
    var open by mutableStateOf<StockItem?>(null)
    /** A stock card's food to open in the food editor (then back to the card). */
    var editFood by mutableStateOf<StockItem?>(null)
    /** A food stock item being turned into a supplement. */
    var moving by mutableStateOf<StockItem?>(null)
    /** Text to start the Find & add search with ("Add anyway" on a bill line). */
    var searchText by mutableStateOf("")
    /** The bill line whose stock card is open (from the review): saving checks it and returns to the review. */
    var billLine by mutableStateOf<app.shoku.data.BillImport.Line?>(null)
}

/** Words that mark a supplement rather than a food, so "add to stock" starts on Supplement. */
private val SUPPLEMENT_WORDS = listOf(
    "creatine", "kreatin", "vitamin", "multivitamin", "omega", "fish oil", "magnesium", "zinc", "iron", "calcium", "b12", "d3",
    "ashwagandha", "melatonin", "electrolyte", "collagen", "probiotic", "capsule", "tablet", "kapsel", "tablette", "supplement",
)

fun looksLikeSupplement(food: app.shoku.data.db.Food): Boolean {
    val n = app.shoku.domain.searchKey("${food.name} ${food.brand.orEmpty()}")
    return SUPPLEMENT_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(n) }
}

/** A new supplement from a food: name, brand as the detail, dose = its scoop or serving if it has one. */
private fun supplementFrom(food: app.shoku.data.db.Food, lib: app.shoku.domain.Library, position: Int): app.shoku.data.db.Supplement {
    val scoop = lib.portionsOf(food).firstOrNull { it.label in setOf("scoop", "serving") }
    return app.shoku.data.db.Supplement(
        java.util.UUID.randomUUID().toString(), food.name, detail = food.brand.orEmpty(), unit = food.unit,
        dose = scoop?.qty ?: 1.0, startDate = java.time.LocalDate.now().toString(), position = position, slot = app.shoku.data.db.Meals.BREAKFAST,
    )
}

/** "Food · Supplement" at the top of the add-to-stock sheets. */
@Composable
private fun KindSwitch(supplement: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Text("It's a", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = app.shoku.ui.theme.ShokuColors.Muted)
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
    app.shoku.ui.components.Segmented(listOf("Food", "Supplement"), if (supplement) 1 else 0) { onChange(it == 1) }
}

/** Scans a pack into stock (library first, then Open Food Facts). */
@Composable
fun rememberStockScan(vm: PlanViewModel): () -> Unit =
    // The shared scanner: unknown packs and packs without nutrition go to the label photo or typed values.
    app.shoku.ui.library.rememberFoodCapture { food -> vm.startAmount(food) }.scan

/** The sheets behind the stock list: add by search, say it, how much, and editing one item. */
@Composable
fun StockSheets(vm: PlanViewModel, ui: StockUi, onStockAdded: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val library by vm.library.collectAsState()
    val stock by vm.stockView.collectAsState()
    val amountFor by vm.amountFor.collectAsState()
    val supps by vm.supplements.collectAsState()
    val sayWorking by vm.sayWorking.collectAsState()
    if (sayWorking) app.shoku.ui.components.BusyOverlay("Working out what you have…", onCancel = vm::cancelSay)
    val lib = library
    if (ui.searching && lib != null) {
        val reference = (context.applicationContext as ShokuApplication).reference
        val replacing = vm.replacingBill
        app.shoku.ui.today.FoodSearchSheet(
            if (replacing != null) "Replace ${replacing.name}" else "Add to stock",
            replacing?.let { "On the bill: ${it.said}" }, lib, reference,
            onPick = { ui.searching = false; ui.searchText = ""; vm.pickForStock(it) },
            onDismiss = { ui.searching = false; ui.searchText = ""; if (vm.replacingBill != null) { vm.replacingBill = null; app.shoku.data.BillImport.openReview.value = true } },
            foodsOnly = true, initialQuery = ui.searchText,
        )
    }
    if (ui.saying) {
        app.shoku.ui.add.DescribeSheet(
            onSubmit = { ui.saying = false; vm.sayStock(it) }, onDismiss = { ui.saying = false },
            title = "What do you have?", hint = "Amounts help: “2 kg chana dal, 30 eggs, 1 l milk, half a jar of ghee”.",
            placeholder = "e.g. 1 kg basmati, 6 eggs, 500 g skyr", button = "Add to stock",
        )
    }
    amountFor?.let { (food, qty) ->
        if (lib != null && lib.foods.containsKey(food.id)) {
            var asSupplement by androidx.compose.runtime.remember(food.id) { mutableStateOf(looksLikeSupplement(food)) }
            var place by androidx.compose.runtime.remember(food.id) { mutableStateOf(vm.placeFor(food)) }
            if (asSupplement) {
                val draft = androidx.compose.runtime.remember(food.id) { supplementFrom(food, lib, supps.supplements.size) }
                SupplementEditor(
                    draft, supps, isNew = true,
                    initialCount = lib.portionsOf(food).firstOrNull { it.label == "pack" }?.qty,
                    header = { KindSwitch(true) { asSupplement = it } },
                    onSave = { s, count, on ->
                        vm.foodToSupplement(food, s, count, on)
                        android.widget.Toast.makeText(context, "${s.name} added to Supplements", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { vm.amountFor.value = null },
                )
                return@let
            }
            app.shoku.ui.components.QuantitySheet(
                header = {
                    KindSwitch(false) { asSupplement = it }
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                    androidx.compose.material3.Text("Where", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = app.shoku.ui.theme.ShokuColors.Muted)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        app.shoku.data.db.StockPlaces.ALL.dropLast(1).forEach { p ->
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { place = p }) {
                                app.shoku.ui.components.StatusChip(p, if (place == p) app.shoku.ui.components.ChipStyle.Filled else app.shoku.ui.components.ChipStyle.Tinted)
                            }
                        }
                    }
                },
                title = food.name, subtitle = listOfNotNull(food.brand, "add to stock").joinToString(" · "),
                units = lib.unitsFor(app.shoku.data.db.RefType.FOOD, food.id), initialUnit = food.unit, initialQty = qty,
                initialStatus = app.shoku.data.db.EntryStatus.EATEN,
                nutrientsFor = { u, q -> lib.entryNutrients(app.shoku.data.db.RefType.FOOD, food.id, u, q) },
                confirmLabel = "Add to stock", showStatus = false,
                onConfirm = { r ->
                    vm.addStock(food, r.unit, r.qty, place)
                    vm.replacingBill?.let { line ->
                        vm.replacingBill = null
                        val shoku = context.applicationContext as ShokuApplication
                        shoku.appScope.launch { app.shoku.data.BillImport.replaced(shoku, line, food, lib.inFoodUnit(food.id, r.unit, r.qty) ?: r.qty) }
                        app.shoku.data.BillImport.openReview.value = true // back to the review
                    }
                    val msg = "Added ${app.shoku.ui.formatUnitQty(r.qty, r.unit)} ${food.name} to stock"
                    if (onStockAdded != null) onStockAdded(msg)
                    else android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    vm.amountFor.value = null
                    if (vm.replacingBill != null) { vm.replacingBill = null; app.shoku.data.BillImport.openReview.value = true }
                },
            )
        }
    }
    ui.open?.let { item ->
        stock.levels.firstOrNull { it.item.id == item.id }?.let { level ->
            StockItemEditor(
                level,
                onSave = { i, n ->
                    vm.saveStock(i, n); ui.open = null
                    // Edited from the bill review: that line is checked; back to the review.
                    ui.billLine?.let { line -> app.shoku.data.BillImport.looksRight(line); ui.billLine = null; app.shoku.data.BillImport.openReview.value = true }
                    android.widget.Toast.makeText(context, "${i.name}: stock updated", android.widget.Toast.LENGTH_SHORT).show()
                    app.shoku.ui.search.SearchReturn.go()
                },
                onDelete = {
                    vm.deleteStock(it); ui.open = null
                    android.widget.Toast.makeText(context, "${it.name} removed from stock", android.widget.Toast.LENGTH_SHORT).show()
                    app.shoku.ui.search.SearchReturn.go()
                },
                onDismiss = {
                    ui.open = null; app.shoku.ui.search.SearchReturn.go()
                    if (ui.billLine != null) { ui.billLine = null; app.shoku.data.BillImport.openReview.value = true }
                },
                onEditFood = item.foodId?.takeIf { lib?.foods?.containsKey(it) == true }?.let { { ui.editFood = item; ui.open = null } },
                onAddUnit = item.foodId?.takeIf { lib?.foods?.containsKey(it) == true }?.let { fid -> { label: String, size: Double -> vm.addUnit(fid, label, size) } },
                units = item.foodId?.let { stock.units[it] }.orEmpty(),
                onMoveToSupplements = item.foodId?.takeIf { lib?.foods?.containsKey(it) == true }?.let { { ui.moving = item; ui.open = null } },
            )
        }
    }
    // A food already in stock that is really a supplement: same editor, counted from what's there now.
    ui.moving?.let { item ->
        val food = item.foodId?.let { lib?.foods?.get(it) }
        if (food == null || lib == null) { ui.moving = null; return@let }
        val draft = androidx.compose.runtime.remember(item.id) { supplementFrom(food, lib, supps.supplements.size) }
        SupplementEditor(
            draft, supps, isNew = true,
            initialCount = stock.levels.firstOrNull { it.item.id == item.id }?.qty,
            onSave = { s, count, on ->
                vm.foodToSupplement(food, s, count, on); ui.moving = null
                android.widget.Toast.makeText(context, "${s.name} moved to Supplements", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { ui.moving = null },
        )
    }
}
