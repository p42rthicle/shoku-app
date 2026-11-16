package app.shoku.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.FoodKind
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.data.reference.ReferenceData
import app.shoku.data.reference.ReferenceFood
import app.shoku.data.reference.ScannedProduct
import app.shoku.domain.Library
import app.shoku.domain.matchScore
import app.shoku.domain.searchKey
import app.shoku.ui.components.NameWithTag
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import app.shoku.data.reference.toLibraryFood

/** What was picked to replace an entry with. */
sealed interface ReplacePick {
    data class Own(val refType: String, val refId: String) : ReplacePick
    data class Reference(val food: ReferenceFood) : ReplacePick
    data class Online(val product: ScannedProduct) : ReplacePick
}

private data class Row3(val title: String, val tag: String?, val subtitle: String, val pick: ReplacePick)

/** Copies a picked database food or online product into the library (if needed) and returns its type and id. */
suspend fun app.shoku.data.ShokuRepository.resolvePick(pick: ReplacePick): Pair<String, String> {
    val (type, id) = when (pick) {
        is ReplacePick.Own -> pick.refType to pick.refId
        is ReplacePick.Reference -> RefType.FOOD to adoptReference(pick.food)
        is ReplacePick.Online -> {
            val (food, portions) = pick.product.toLibraryFood()
            saveFood(food, updatePast = false)
            if (portions.isNotEmpty()) savePortions(portions)
            RefType.FOOD to food.id
        }
    }
    if (type == RefType.FOOD) library.first { it.foods.containsKey(id) }
    return type to id
}

/** Search your foods, the food databases and Open Food Facts for something to put in place of [entry]. */
@Composable
fun ReplaceSheet(entry: LogEntry, library: Library, reference: ReferenceData, onPick: (ReplacePick) -> Unit, onDismiss: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.shoku.ShokuApplication
    val all by app.repository.allEntries.collectAsState(initial = emptyList())
    // What's been eaten at this meal in the last 30 days, most often first.
    val from = java.time.LocalDate.now().minusDays(30).toString()
    val recent = remember(all, entry.meal) {
        all.filter { !it.deleted && it.meal == entry.meal && it.date >= from && it.refId != null && it.id != entry.id && it.refType != RefType.QUICK }
            .groupBy { it.refType to it.refId!! }.entries.sortedByDescending { e -> e.value.map { it.date }.distinct().size }
            .map { it.key }.take(8)
    }
    FoodSearchSheet("Replace", entry.name, library, reference, onPick, onDismiss, recent = recent, recentLabel = "Recent at ${entry.meal.lowercase()}")
}

/**
 * Search sheet over your foods, dishes and batches, the food databases and Open Food Facts: the one
 * search used wherever a food is chosen. [browse] lists your own items before anything is typed;
 * [excludeDishId] leaves a dish out (a dish can't contain itself); [hint] is a line under the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchSheet(
    title: String,
    subtitle: String?,
    library: Library,
    reference: ReferenceData,
    onPick: (ReplacePick) -> Unit,
    onDismiss: () -> Unit,
    foodsOnly: Boolean = false,
    browse: Boolean = false,
    excludeDishId: String? = null,
    hint: String? = null,
    /** Extra rows at the top, e.g. "Cook a new dish" where that makes sense. */
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
    /** Shown before anything is typed (type, id), under [recentLabel]. */
    recent: List<Pair<String, String>> = emptyList(),
    recentLabel: String = "Recent",
    initialQuery: String = "",
) {
    // Scan, label photo and typed foods: each ends saved in the library, then picked like any other food.
    val capture = app.shoku.ui.library.rememberFoodCapture { food -> onPick(ReplacePick.Own(RefType.FOOD, food.id)) }
    var query by remember { mutableStateOf(initialQuery) }
    var online by remember { mutableStateOf<List<ScannedProduct>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Online search after typing pauses (free, but rate-limited by Open Food Facts).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) { online = emptyList(); searching = false; return@LaunchedEffect }
        delay(600)
        searching = true
        online = runCatching { OpenFoodFacts.search(q) }.getOrDefault(emptyList())
        searching = false
    }

    // Local results after a short pause in typing, computed off the main thread (typing stays smooth).
    val q = searchKey(query.trim())
    val local by app.shoku.ui.components.rememberSearchResults(q, emptyList<Row3>() to emptyList<Row3>(), library, browse, foodsOnly, excludeDishId) { q ->
    val own = if (q.isEmpty()) {
        if (!browse) emptyList() else buildList {
            library.foods.values.filter { !it.archived && !it.deleted }
                .sortedWith(compareBy({ it.kind != FoodKind.GENERIC }, { it.name.lowercase() }))
                .forEach { f -> add(Row3(f.name, if (f.kind == FoodKind.GENERIC) "Any brand" else f.brand, "per ${app.shoku.ui.formatUnitQty(f.baseQty, f.unit)} · ${f.kcal?.let { Math.round(it) } ?: "?"} kcal", ReplacePick.Own(RefType.FOOD, f.id))) }
            if (!foodsOnly) library.dishes.values.filter { !it.archived && !it.deleted && it.id != excludeDishId }.sortedBy { it.name.lowercase() }
                .forEach { d -> add(Row3(d.name, null, "Dish", ReplacePick.Own(RefType.DISH, d.id))) }
            if (!foodsOnly) library.batches.values.filter { !it.deleted && !it.finished }.sortedByDescending { it.date }
                .forEach { b -> add(Row3(b.dishName, null, "Batch · ${b.date}", ReplacePick.Own(RefType.BATCH, b.id))) }
        }.take(60)
    } else buildList {
        library.foods.values.filter { !it.archived && !it.deleted }.forEach { f ->
            val parent = f.parentId?.let { library.foods[it] }
            val s = matchScore(q, searchKey("${f.name} ${f.brand.orEmpty()}"), searchKey(f.aliases), searchKey("${parent?.name.orEmpty()} ${parent?.aliases.orEmpty()}"))
            if (s > 0) add(s to Row3(f.name, if (f.kind == FoodKind.GENERIC) "Any brand" else f.brand, "per ${app.shoku.ui.formatUnitQty(f.baseQty, f.unit)} · ${f.kcal?.let { Math.round(it) } ?: "?"} kcal", ReplacePick.Own(RefType.FOOD, f.id)))
        }
        if (!foodsOnly) library.dishes.values.filter { !it.archived && !it.deleted && it.id != excludeDishId }.forEach { d ->
            val s = matchScore(q, searchKey(d.name), "")
            if (s > 0) add(s to Row3(d.name, null, "Dish", ReplacePick.Own(RefType.DISH, d.id)))
        }
        if (!foodsOnly) library.batches.values.filter { !it.deleted && !it.finished }.forEach { b ->
            val s = matchScore(q, searchKey(b.dishName), "")
            if (s > 0) add(s to Row3(b.dishName, null, "Batch · ${b.date}", ReplacePick.Own(RefType.BATCH, b.id)))
        }
    }.sortedByDescending { it.first }.take(30).map { it.second }
    val refs = if (q.isEmpty()) emptyList() else reference.search(q, limit = 8).filter { !library.hasLive(it.libraryId) }.map { r ->
        Row3(r.name, null, "${if (r.source == "indb") "Indian dish" else "German food database"} · ${Math.round(r.per100.kcal ?: 0.0)} kcal/100 g", ReplacePick.Reference(r))
    }
    own to refs
    }
    val own = local.first
    val refs = local.second
    val known = library.foods.values.mapNotNull { it.barcode }.toSet()
    val onlineRows = online.filter { it.barcode !in known }.map { p ->
        Row3(p.cleanName(), p.brand, "per 100 ${p.unit} · ${p.kcal?.let { Math.round(it) } ?: "?"} kcal", ReplacePick.Online(p))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 20.dp).imePadding()) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(ShokuShapes.Button).background(ShokuColors.Tint).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = ShokuColors.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search your foods, databases, online", style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ShokuColors.Ink),
                        cursorBrush = SolidColor(ShokuColors.Ink),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceChip("Scan", Icons.Outlined.QrCodeScanner, Modifier.weight(1f), capture.scan)
                SourceChip("Label", Icons.Outlined.ReceiptLong, Modifier.weight(1f), capture.label)
                SourceChip("Pack", Icons.Outlined.Inventory2, Modifier.weight(1f), capture.pack)
                SourceChip("New", Icons.Rounded.Add, Modifier.weight(1f)) { capture.newFood(query.trim()) }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (q.isEmpty() && recent.isNotEmpty()) {
                    val rows = recent.mapNotNull { (type, id) ->
                        val name = library.nameOf(type, id) ?: return@mapNotNull null
                        val f = if (type == RefType.FOOD) library.foods[id] else null
                        Row3(name, f?.brand, when (type) { RefType.DISH -> "Dish"; RefType.BATCH -> "Batch"; else -> f?.let { "per ${app.shoku.ui.formatUnitQty(it.baseQty, it.unit)} · ${it.kcal?.let { k -> Math.round(k) } ?: "?"} kcal" } ?: "" }, ReplacePick.Own(type, id))
                    }
                    if (rows.isNotEmpty()) { item { SectionLabel(recentLabel) }; items(rows) { PickRow(it, onPick) } }
                }
                extraActions.forEach { (label, action) ->
                    item { Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable(onClick = action).padding(vertical = 12.dp)) }
                }
                if (own.isNotEmpty()) { item { SectionLabel("Your foods") }; items(own) { PickRow(it, onPick) } }
                if (refs.isNotEmpty()) { item { SectionLabel("Food databases", trailing = "typical values") }; items(refs) { PickRow(it, onPick) } }
                if (onlineRows.isNotEmpty() || searching) item { SectionLabel("Packaged products", trailing = if (searching) "searching…" else "Open Food Facts") }
                items(onlineRows) { PickRow(it, onPick) }
                if (q.isNotEmpty() && own.isEmpty() && refs.isEmpty() && onlineRows.isEmpty() && !searching) {
                    item { Text("Nothing found. Try fewer words or the brand, scan it, or add it as a new food.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                }
                if (q.isNotEmpty()) item {
                    Text("+ New food “${query.trim()}”", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { capture.newFood(query.trim()) }.padding(vertical = 12.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(ShokuShapes.Button).background(ShokuColors.Tint).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = ShokuColors.Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun PickRow(r: Row3, onPick: (ReplacePick) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { onPick(r.pick) }.padding(vertical = 10.dp)) {
        NameWithTag(r.title, r.tag, MaterialTheme.typography.titleMedium)
        Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
