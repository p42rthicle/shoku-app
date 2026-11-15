package app.shoku.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.shoku.ShokuApplication
import app.shoku.data.db.StockItem

/** Which stock sheet is open (search to add, say it, edit one item). */
class StockUi {
    var searching by mutableStateOf(false)
    var saying by mutableStateOf(false)
    var open by mutableStateOf<StockItem?>(null)
}

/** Scans a pack into stock (library first, then Open Food Facts). */
@Composable
fun rememberStockScan(vm: PlanViewModel): () -> Unit =
    // The shared scanner: unknown packs and packs without nutrition go to the label photo or typed values.
    app.shoku.ui.library.rememberFoodCapture { food -> vm.startAmount(food) }.scan

/** The sheets behind the stock list: add by search, say it, how much, and editing one item. */
@Composable
fun StockSheets(vm: PlanViewModel, ui: StockUi) {
    val context = LocalContext.current
    val library by vm.library.collectAsState()
    val stock by vm.stockView.collectAsState()
    val amountFor by vm.amountFor.collectAsState()
    val sayWorking by vm.sayWorking.collectAsState()
    if (sayWorking) app.shoku.ui.components.BusyOverlay("Working out what you have…", onCancel = vm::cancelSay)
    val lib = library
    if (ui.searching && lib != null) {
        val reference = (context.applicationContext as ShokuApplication).reference
        app.shoku.ui.today.FoodSearchSheet("Add to stock", null, lib, reference, onPick = { ui.searching = false; vm.pickForStock(it) }, onDismiss = { ui.searching = false }, foodsOnly = true)
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
            app.shoku.ui.components.QuantitySheet(
                title = food.name, subtitle = listOfNotNull(food.brand, "add to stock").joinToString(" · "),
                units = lib.unitsFor(app.shoku.data.db.RefType.FOOD, food.id), initialUnit = food.unit, initialQty = qty,
                initialStatus = app.shoku.data.db.EntryStatus.EATEN,
                nutrientsFor = { u, q -> lib.entryNutrients(app.shoku.data.db.RefType.FOOD, food.id, u, q) },
                confirmLabel = "Add to stock", showStatus = false,
                onConfirm = { r -> vm.addStock(food, r.unit, r.qty) },
                onDismiss = { vm.amountFor.value = null },
            )
        }
    }
    ui.open?.let { item ->
        stock.levels.firstOrNull { it.item.id == item.id }?.let { level ->
            StockItemEditor(
                level, onSave = { i, n -> vm.saveStock(i, n); ui.open = null }, onDelete = { vm.deleteStock(it); ui.open = null }, onDismiss = { ui.open = null },
                units = item.foodId?.let { stock.units[it] }.orEmpty(),
            )
        }
    }
}
