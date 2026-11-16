package app.shoku.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.ShokuApplication
import app.shoku.data.db.FoodKind
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.data.reference.ReferenceFood
import app.shoku.data.reference.ScannedProduct
import app.shoku.domain.Library
import app.shoku.domain.matchScore
import app.shoku.domain.matchesAll
import app.shoku.domain.searchKey
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.NameWithTag
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SeeMoreChip
import app.shoku.ui.components.rememberSearchResults
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.formatUnitQty
import app.shoku.ui.shortLabel
import app.shoku.ui.plan.stockLine
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.delay
import java.time.LocalDate

/** The global-search button: a magnifying glass with a spark, so it reads as "search everything". */
@Composable
fun GlobalSearchButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(ShokuShapes.Tile).border(1.dp, ShokuColors.Line, ShokuShapes.Tile).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search everything", tint = ShokuColors.Ink, modifier = Modifier.size(22.dp))
        Icon(Icons.Outlined.AutoAwesome, null, tint = ShokuColors.Ink, modifier = Modifier.size(12.dp).align(Alignment.TopEnd).offset(x = (-7).dp, y = 7.dp))
    }
}

/**
 * What the search screen keeps while you look at a result (query, open sections, scroll), so Back from the
 * result returns to the same search.
 */
class SearchUi {
    var query by mutableStateOf("")
    var openSections by mutableStateOf(emptySet<String>())
    val list = androidx.compose.foundation.lazy.LazyListState()
    fun reset() { query = ""; openSections = emptySet(); list.requestScrollToItem(0) }
}

/**
 * Set when search opened something (a food, the stock card, a dish): closing it goes back to the search
 * instead of staying in the Library. Cleared when you switch tabs yourself.
 */
object SearchReturn {
    var action: (() -> Unit)? = null
    /** Back to the search if it opened what's closing; true if it did. */
    fun go(): Boolean { val a = action ?: return false; action = null; a(); return true }
}

/** Where a search result leads. */
class SearchActions(
    val openDay: (LocalDate) -> Unit,
    val openFood: (String) -> Unit,
    val openDish: (String) -> Unit,
    val openBatch: (String) -> Unit,
    val openStock: (String) -> Unit,
    val openTemplate: (String) -> Unit,
    val openSupplement: (String) -> Unit,
    val addReference: (ReferenceFood) -> Unit,
    val addOnline: (ScannedProduct) -> Unit,
    val log: (String) -> Unit,
)

private data class Hit(val title: String, val tag: String?, val subtitle: String, val onClick: () -> Unit)

/**
 * One search over everything: what's at home, what you've logged, your foods, dishes and batches, meal
 * templates, supplements, the food databases and Open Food Facts. Tapping a result opens it where it lives.
 */
@Composable
fun GlobalSearchScreen(library: Library?, planVm: app.shoku.ui.plan.PlanViewModel, actions: SearchActions, onClose: () -> Unit, ui: SearchUi = remember { SearchUi() }) {
    BackHandler(onBack = onClose)
    val app = LocalContext.current.applicationContext as ShokuApplication
    val entries by app.repository.allEntries.collectAsState(initial = emptyList())
    val stock by planVm.stockView.collectAsState()
    val plan by planVm.plan.collectAsState()
    val supps by planVm.supplements.collectAsState()
    var query by ui::query
    var online by remember { mutableStateOf<List<ScannedProduct>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var openSections by ui::openSections
    val focus = remember { FocusRequester() }
    // A new search starts typing; coming back to one shows the results where you left them.
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    // Open Food Facts allows about 10 searches a minute: wait for a pause in typing.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) { online = emptyList(); onlineLoading = false; return@LaunchedEffect }
        delay(900)
        onlineLoading = true
        online = runCatching { OpenFoodFacts.search(q) }.getOrDefault(emptyList())
        onlineLoading = false
    }

    val q = searchKey(query.trim())
    fun hit(name: String) = matchesAll(searchKey(name), query.trim())
    // Everything local is searched after a short pause in typing, off the main thread; online results join as they arrive.
    val local by rememberSearchResults(q, emptyList<Pair<String, List<Hit>>>(), library, entries, stock, plan, supps) { q -> if (q.isEmpty() || library == null) emptyList() else buildList {
        add("At home" to stock.levels.filter { it.item.supplementId == null && hit(it.item.name) }.map { l ->
            val i = l.item
            val amount = if (!i.tracked) when (i.status) { "low" -> "Running low"; "out" -> "Out"; else -> "Have it" }
                else l.qty?.let { "≈ ${formatUnitQty(Math.round(it * 10) / 10.0, i.unit)}" } ?: "Not counted"
            Hit(i.name, null, "${i.place} · $amount") { actions.openStock(i.id) }
        })
        add("Logged" to entries.filter { !it.deleted && (hit(it.name) || it.note?.let(::hit) == true || it.variant?.let(::hit) == true) }
            .sortedWith(compareByDescending<app.shoku.data.db.LogEntry> { it.date }.thenByDescending { it.createdAt }).take(40).map { e ->
                val d = LocalDate.parse(e.date)
                Hit(e.name, e.variant, "${e.meal} · ${d.shortLabel()} · ${formatUnitQty(e.qty, e.unit)}${e.kcal?.let { " · ${Math.round(it)} kcal" } ?: ""}${e.note?.let { " · 📝 $it" } ?: ""}") { actions.openDay(d) }
            })
        add("Your foods" to library.foods.values.filter { !it.deleted && !it.archived }
            .map { f -> f to matchScore(q, searchKey("${f.name} ${f.brand.orEmpty()}"), searchKey(f.aliases)) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(30).map { (f, _) ->
                Hit(f.name, if (f.kind == FoodKind.GENERIC) "Any brand" else f.brand, "per ${formatUnitQty(f.baseQty, f.unit)} · ${f.kcal?.let { Math.round(it) } ?: "?"} kcal") { actions.openFood(f.id) }
            })
        add("Dishes and batches" to (
            library.dishes.values.filter { !it.deleted && hit(it.name) }.map { d -> Hit(d.name, null, if (d.archived) "Dish · archived" else "Dish") { actions.openDish(d.id) } } +
                library.batches.values.filter { !it.deleted && hit(it.dishName) }.sortedByDescending { it.date }
                    .map { b -> Hit(b.dishName, null, "Batch · ${LocalDate.parse(b.date).shortLabel()}${if (b.finished) " · finished" else ""}") { actions.openBatch(b.id) } }
            ))
        add("Meal plan" to plan.templates.filter { t ->
            !t.deleted && (hit(t.name) || plan.itemsOf(t.id).any { i -> i.refId?.let { library.nameOf(i.refType, it) }?.let(::hit) == true })
        }.map { t -> Hit(t.name, null, "${t.meal} template") { actions.openTemplate(t.id) } })
        add("Supplements" to supps.supplements.filter { hit(it.name) }.map { s -> Hit(s.name, null, stockLine(s, supps)) { actions.openSupplement(s.id) } })
        add("Food databases" to app.reference.search(q, limit = 6).filter { !library.hasLive(it.libraryId) }.map { r ->
            Hit(r.name, null, "${if (r.source == "indb") "Indian dish" else "German food database"} · ${Math.round(r.per100.kcal ?: 0.0)} kcal/100 g · tap to add") { actions.addReference(r) }
        })
    } }
    val known = library?.foods?.values?.mapNotNull { it.barcode }?.toSet().orEmpty()
    val sections: List<Pair<String, List<Hit>>> = (if (q.isEmpty()) local else local + listOf("Packaged products" to online.filter { it.barcode !in known }.map { p ->
        Hit(p.cleanName(), p.brand, "per 100 ${p.unit} · ${p.kcal?.let { Math.round(it) } ?: "?"} kcal · tap to add") { actions.addOnline(p) }
    })).filter { it.second.isNotEmpty() || (it.first == "Packaged products" && onlineLoading) }

    Column(Modifier.fillMaxSize().background(ShokuColors.Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.padding(horizontal = ShokuSpacing.Screen).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onClose)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Search everything", style = MaterialTheme.typography.headlineMedium)
                    Text("Home, log, library, plan, databases, online", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(ShokuShapes.Button).background(ShokuColors.Tint).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = ShokuColors.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Pasta, kadhi, magnesium…", style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Muted)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ShokuColors.Ink),
                        cursorBrush = SolidColor(ShokuColors.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = ShokuColors.Muted, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { query = "" })
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = ui.list,
            contentPadding = PaddingValues(horizontal = ShokuSpacing.Screen, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
        ) {
            if (q.isEmpty()) {
                item { Text("Type to search what's at home, what you've logged, your foods and dishes, your plan and supplements, the food databases and Open Food Facts.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
            } else {
                item(key = "log") {
                    OutlinedCard(Modifier.fillMaxWidth(), onClick = { actions.log(query.trim()) }) {
                        Text("Log “${query.trim()}”", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Opens Add for this meal with the search filled in", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    }
                }
                if (sections.isEmpty() && !onlineLoading) item { Text("Nothing matches “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                sections.forEach { (title, hits) ->
                    val open = title in openSections
                    item(key = "label-$title") {
                        SectionLabel(title, trailing = if (title == "Packaged products" && onlineLoading) "searching…" else "${hits.size}")
                    }
                    if (hits.isNotEmpty()) item(key = "hits-$title") {
                        OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                            hits.let { if (open) it else it.take(3) }.forEachIndexed { i, h ->
                                if (i > 0) Hairline()
                                Column(Modifier.fillMaxWidth().clickable(onClick = h.onClick).padding(vertical = 11.dp)) {
                                    NameWithTag(h.title, h.tag, MaterialTheme.typography.titleMedium)
                                    Text(h.subtitle, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item(key = "more-$title") {
                        SeeMoreChip(hits.size, 3, open, { openSections = if (open) openSections - title else openSections + title })
                    }
                }
            }
        }
    }
}
