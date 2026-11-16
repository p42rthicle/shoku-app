package app.shoku.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.shoku.ShokuApplication
import app.shoku.data.db.Food
import app.shoku.domain.Duplicates
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.formatQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val PREFS = "duplicates"

/** Groups the person said aren't duplicates ("Not the same"). */
fun ignoredDuplicates(context: android.content.Context): Set<String> =
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getStringSet("ignored", emptySet()).orEmpty()

/**
 * Settings › Duplicate foods: every product that's in the library more than once, the suggested one to keep
 * (logged most; else most complete) and whose numbers to use, merged in one tap. Past entries keep their numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ShokuApplication
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var ignored by remember { mutableStateOf(prefs.getStringSet("ignored", emptySet()).orEmpty()) }
    val data by remember {
        combine(app.repository.library, app.repository.allEntries, app.home.stock) { lib, logs, (stock, _) -> Triple(lib, logs, stock.filter { !it.deleted }.mapNotNull { it.foodId }.toSet()) }
    }.collectAsState(initial = null)
    var groups by remember { mutableStateOf<List<Duplicates.Group>?>(null) }
    var merged by remember { mutableStateOf(0) }
    // Per group: which food to keep and whose numbers to use, when changed from the suggestion.
    val keepChoice = remember { mutableStateMapOf<String, String>() }
    val valuesChoice = remember { mutableStateMapOf<String, String>() }
    val nameChoice = remember { mutableStateMapOf<String, String>() }

    androidx.compose.runtime.LaunchedEffect(data, ignored) {
        val d = data ?: return@LaunchedEffect
        groups = kotlinx.coroutines.withContext(Dispatchers.Default) { Duplicates.find(d.first, d.second, d.third, ignored) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        LazyColumn(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Duplicate foods", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Foods that are in your library more than once. Merging keeps one: past entries keep their numbers and now point to it, " +
                        "and so do your dishes, batches, meal plan and stock. Tap a food to keep it instead.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
                if (merged > 0) { Spacer(Modifier.height(6.dp)); Text("Merged $merged so far.", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink) }
            }
            val gs = groups
            when {
                gs == null -> item { Text("Looking…", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                gs.isEmpty() -> item { Text("No duplicates. One entry for each product.", style = MaterialTheme.typography.bodyLarge) }
                else -> {
                    item { Text("${gs.size} ${if (gs.size == 1) "product" else "products"} found more than once", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted) }
                    gs.forEach { g ->
                        item(key = g.key) {
                            val lib = data?.first ?: return@item
                            val keepId = keepChoice[g.key] ?: g.keep.food.id
                            val valuesId = valuesChoice[g.key] ?: g.valuesFrom.food.id
                            val keep = g.members.first { it.food.id == keepId }.food
                            val values = g.members.first { it.food.id == valuesId }.food
                            val name = nameChoice[g.key] ?: Duplicates.bestName(g.members.map { it.food }, keep, values)
                            val plan = remember(g.key, keepId, valuesId, name, lib) { Duplicates.plan(lib, g, keep, values, name) }
                            GroupCard(
                                g, keepId, valuesId, name, plan,
                                onKeep = { keepChoice[g.key] = it },
                                onValues = { valuesChoice[g.key] = it },
                                onName = { nameChoice[g.key] = it },
                                onMerge = {
                                    scope.launch {
                                        app.repository.mergeFoods(plan)
                                        merged++
                                        android.widget.Toast.makeText(context, "Merged into ${plan.food.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onIgnore = {
                                    ignored = ignored + g.key
                                    prefs.edit().putStringSet("ignored", ignored).apply()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    g: Duplicates.Group,
    keepId: String,
    valuesId: String,
    name: String,
    plan: Duplicates.Merge,
    onKeep: (String) -> Unit,
    onValues: (String) -> Unit,
    onName: (String) -> Unit,
    onMerge: () -> Unit,
    onIgnore: () -> Unit,
) {
    val keep = g.members.first { it.food.id == keepId }
    Column(
        Modifier.fillMaxWidth().clip(ShokuShapes.Card).border(1.dp, ShokuColors.Line, ShokuShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        g.members.forEach { m ->
            val kept = m.food.id == keepId
            Row(
                Modifier.fillMaxWidth().clip(ShokuShapes.Button)
                    .background(if (kept) ShokuColors.Tint else ShokuColors.Paper)
                    .clickable { onKeep(m.food.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.food.name + (m.food.brand?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    Text(describe(m), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                }
                Spacer(Modifier.width(8.dp))
                if (kept) StatusChip("Keep", ChipStyle.Filled)
            }
        }
        val suggested = keepId == g.keep.food.id
        Text(
            (if (suggested) "Suggested: keep ${keep.food.name}, ${g.keepReason}." else "Keeping ${keep.food.name}.") +
                (if (valuesId != keepId) " Numbers from ${g.members.first { it.food.id == valuesId }.food.name}" +
                    (if (valuesId == g.valuesFrom.food.id) g.valuesReason?.let { " ($it)" }.orEmpty() else "") + "." else ""),
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Ink,
        )
        if (g.members.size > 1 && g.members.any { it.food.unit != g.members.first().food.unit || it.food.kcal != g.members.first().food.kcal }) {
            Text("Use the numbers of", style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.members.forEach { m ->
                    Column(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onValues(m.food.id) }) {
                        StatusChip(per(m.food), if (m.food.id == valuesId) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
            }
        }
        val names = (listOf(name) + g.names).distinctBy { it.lowercase() }
        if (names.size > 1) {
            Text("Name", style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { n ->
                    Column(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onName(n) }) {
                        StatusChip(n, if (n == name) ChipStyle.Filled else ChipStyle.Tinted)
                    }
                }
            }
        }
        // Exactly what the one food will be.
        val f = plan.food
        Column(Modifier.fillMaxWidth().clip(ShokuShapes.Button).background(ShokuColors.Tint).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Result", style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted)
            Text(f.name + (f.brand?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    per(f),
                    f.protein?.let { "${formatQty(Math.round(it * 10) / 10.0)} g protein" },
                    f.carbs?.let { "${formatQty(Math.round(it * 10) / 10.0)} g carbs" },
                    f.fat?.let { "${formatQty(Math.round(it * 10) / 10.0)} g fat" },
                    f.fibre?.let { "${formatQty(Math.round(it * 10) / 10.0)} g fibre" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Ink,
            )
            val extra = listOfNotNull(
                plan.portions.takeIf { it.isNotEmpty() }?.joinToString(", ") { "1 ${it.label} = ${formatQty(it.qty)} ${f.unit}" },
                if (!f.barcode.isNullOrBlank()) "barcode kept" else null,
                "${g.members.sumOf { it.logs }} past entries keep their numbers",
            )
            Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Not the same", onClick = onIgnore, modifier = Modifier.weight(1f))
            PrimaryButton("Merge into one", onClick = onMerge, modifier = Modifier.weight(1f))
        }
    }
}

private fun describe(m: Duplicates.Member): String = listOfNotNull(
    if (m.logs == 0) "never logged" else "logged ${m.logs}×" + (m.lastLogged?.let { ", last ${java.time.LocalDate.parse(it).let { d -> "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}" }}" } ?: ""),
    per(m.food),
    when (m.food.source) { "label" -> "from the label"; "off", "openfoodfacts" -> "Open Food Facts"; else -> null },
    if (!m.food.barcode.isNullOrBlank()) "barcode" else null,
    if (m.inStock) "in stock" else null,
    if (m.food.archived) "archived" else null,
).joinToString(" · ")

private fun per(f: Food): String = "${f.kcal?.let { Math.round(it) } ?: "?"} kcal per ${if (f.unit in app.shoku.domain.WEIGHT_UNITS) "${formatQty(f.baseQty)} ${f.unit}" else "1 ${f.unit}"}"
