package app.shoku.ui.settings

import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.ShokuRepository
import app.shoku.data.sync.SyncEngine
import app.shoku.domain.goalFor
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.SummaryCard
import app.shoku.ui.dayMonth
import app.shoku.ui.formatQty
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SettingsState(
    val foods: Int = 0,
    val dishes: Int = 0,
    val batches: Int = 0,
    val entries: Int = 0,
    val goalKcal: Double? = null,
    val goalProtein: Double? = null,
    val goalFrom: String? = null,
    val goalFibre: Double? = null,
)

class SettingsViewModel(private val repo: ShokuRepository, val sync: SyncEngine) : ViewModel() {
    fun syncNow() = viewModelScope.launch { sync.sync() }

    val state = combine(repo.library, repo.allEntries, repo.allGoals) { lib, entries, goals ->
        val g = goalFor(goals, LocalDate.now().toString())
        SettingsState(lib.foods.size, lib.dishes.size, lib.batches.size, entries.size, g?.kcal, g?.protein, g?.effectiveFrom, g?.fibre)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setGoal(kcal: Double, protein: Double, fibre: Double?) = viewModelScope.launch { repo.setGoal(LocalDate.now(), kcal, protein, fibre) }

    private val ai = app.shoku.data.ai.AiClient()
    val aiUsage = kotlinx.coroutines.flow.MutableStateFlow<app.shoku.data.ai.AiUsage?>(null)
    val aiUsageFailed = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun loadUsage() = viewModelScope.launch {
        aiUsageFailed.value = false
        runCatching { ai.usage() }.onSuccess { aiUsage.value = it }.onFailure { aiUsageFailed.value = true }
    }
    fun setCap(usd: Double) = viewModelScope.launch { runCatching { ai.setCap(usd) }; loadUsage() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    contentPadding: PaddingValues,
    onExport: () -> Unit,
    onOpenDesign: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val sync by vm.sync.status.collectAsState()
    var editingGoal by remember { mutableStateOf(false) }

    var showDuplicates by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    if (showMemory) MemorySheet(onDismiss = { showMemory = false })
    if (showDuplicates) DuplicatesSheet(onDismiss = { showDuplicates = false })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item { ScreenTitle("Settings"); Spacer(Modifier.height(8.dp)) }

        item {
            SummaryCard(
                label = "Daily goal",
                value = s.goalKcal?.let { formatQty(it) } ?: "Not set",
                trailing = s.goalKcal?.let { "kcal" },
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    buildString {
                        s.goalProtein?.let { append("${formatQty(it)} g protein") }
                        s.goalFibre?.let { append(" · ${formatQty(it)} g fibre") }
                        s.goalFrom?.let { append(" · since ${LocalDate.parse(it).dayMonth()} ${LocalDate.parse(it).year}") }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton("Change goal from today", onClick = { editingGoal = true })
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("Appearance") }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val modes = listOf("system", "light", "dark")
            app.shoku.ui.components.Segmented(listOf("System", "Light", "Dark"), modes.indexOf(app.shoku.ui.theme.Appearance.mode).coerceAtLeast(0)) { i ->
                app.shoku.ui.theme.Appearance.mode = modes[i]
                context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putString("theme", modes[i]).apply()
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("Meals") }
        item { MealsCard() }

        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                SecondaryButton("Add widget to home screen", onClick = {
                    manager.requestPinAppWidget(android.content.ComponentName(context, app.shoku.ui.widget.TodayWidget::class.java), null, null)
                })
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("Reminders") }
        item { RemindersCard() }

        item { HealthCard() }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("AI this month") }
        item { AiUsageCard(vm) }
        item {
            // What the AI has learned from you (AiMemory): see it, forget what's wrong.
            val n = remember(showMemory) { app.shoku.data.ai.AiMemory.all().size }
            SecondaryButton(if (n == 0) "What Shoku AI has learned" else "What Shoku AI has learned · $n", onClick = { showMemory = true })
        }
        item { ClaudeKeyCard(onChanged = { vm.loadUsage() }) }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("Cloud sync") }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                if (!vm.sync.configured) {
                    // Public builds have no backend: no errors and no Sync button, just what that means.
                    Text("Sync: not set up", style = MaterialTheme.typography.titleLarge)
                    Text("Your data stays on this phone. Export a backup ZIP below to keep a copy.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                    return@OutlinedCard
                }
                Text(
                    when {
                        sync.running -> "Syncing…"
                        sync.lastSyncAt == 0L -> "Not synced yet"
                        else -> "Last synced ${relativeTime(sync.lastSyncAt)}"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    sync.lastError?.let { "Last attempt failed: $it" }
                        ?: if (sync.lastSyncAt > 0) "Sent ${sync.lastPushed}, received ${sync.lastPulled} in the last sync. Syncs when you open or leave the app."
                        else "Your data is copied to your Catalyst project so it survives a lost phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sync.lastError != null) ShokuColors.Over else ShokuColors.Muted,
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton(if (sync.running) "Syncing…" else "Sync now", onClick = { if (!sync.running) vm.syncNow() })
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("Your data") }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row {
                    Count("Foods", s.foods, Modifier.weight(1f))
                    Count("Dishes", s.dishes, Modifier.weight(1f))
                    Count("Batches", s.batches, Modifier.weight(1f))
                    Count("Entries", s.entries, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                // One entry for each product: find foods that are in the library twice and merge them.
                SecondaryButton("Find duplicate foods", onClick = { showDuplicates = true }, icon = Icons.Outlined.ContentCopy)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Export backup ZIP", onClick = onExport, icon = Icons.Outlined.FileDownload)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Export saves a ZIP of CSVs in the old format, as an extra copy you control.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionLabel("App") }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenGuide).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("How to use Shoku", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ShokuColors.Muted)
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenDesign).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Design components", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ShokuColors.Muted)
                }
                Hairline()
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text("Food data sources", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Barcodes: Open Food Facts (openfoodfacts.org), open data under the ODbL. " +
                            "Indian dishes: Indian Nutrient Databank (Vijayakumar, Jaacks et al., 2024), based on ICMR-NIN IFCT 2017; " +
                            "recipes that count all frying oil as eaten are left out. German foods: Bundeslebensmittelschlüssel 4.0 (Max Rubner-Institut, 2025). " +
                            "Everything else is your own data.",
                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                    )
                }
                Hairline()
                // The real version from the build; tap for what's new.
                var changelog by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { changelog = true }.padding(vertical = 14.dp)) {
                    Text("Version", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${app.shoku.BuildConfig.VERSION_NAME} · What's new", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                }
                if (changelog) ChangelogDialog(onDismiss = { changelog = false })
            }
        }
    }

    if (editingGoal) {
        GoalSheet(
            kcal = s.goalKcal ?: 1750.0,
            protein = s.goalProtein ?: 90.0,
            fibre = s.goalFibre,
            onSave = { k, p, f -> vm.setGoal(k, p, f); editingGoal = false },
            onDismiss = { editingGoal = false },
        )
    }
}

@Composable
private fun Count(label: String, n: Int, modifier: Modifier) {
    Column(modifier) {
        Text(n.toString(), style = NumberStyle.Stat)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(kcal: Double, protein: Double, fibre: Double?, onSave: (Double, Double, Double?) -> Unit, onDismiss: () -> Unit) {
    var k by remember { mutableStateOf(formatQty(kcal)) }
    var p by remember { mutableStateOf(formatQty(protein)) }
    var f by remember { mutableStateOf(fibre?.let { formatQty(it) } ?: "") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text("Daily goal", style = MaterialTheme.typography.headlineSmall)
            Text("Applies from today. Past days keep the goal they had.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberBox("Calories", "kcal", k, { k = it }, Modifier.weight(1f))
                NumberBox("Protein", "g", p, { p = it }, Modifier.weight(1f))
                NumberBox("Fibre", "g", f, { f = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            val kv = k.toDoubleOrNull()
            val pv = p.toDoubleOrNull()
            PrimaryButton(if (kv == null || pv == null || kv <= 0) "Add calories and protein first" else "Save goal", onClick = {
                if (kv != null && pv != null && kv > 0) onSave(kv, pv, f.toDoubleOrNull()?.takeIf { it > 0 })
            })
        }
    }
}

@Composable
private fun NumberBox(label: String, unit: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Column(modifier.clip(ShokuShapes.Card).background(ShokuColors.Tint).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = value,
                onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() || it == '.' }) onChange(v) },
                textStyle = MaterialTheme.typography.displaySmall.copy(color = ShokuColors.Ink),
                singleLine = true,
                cursorBrush = SolidColor(ShokuColors.Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

private fun relativeTime(t: Long): String {
    val mins = (System.currentTimeMillis() - t) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        else -> java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().dayMonth()
    }
}

private fun usd(v: Double) = "\$" + String.format(java.util.Locale.US, "%.2f", v)

/** Spending so far against the monthly limit; AI stops when the limit is reached. */
@Composable
private fun AiUsageCard(vm: SettingsViewModel) {
    val u by vm.aiUsage.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadUsage() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        val usage = u
        val failed by vm.aiUsageFailed.collectAsState()
        if (usage == null) {
            Text(if (failed) "Couldn't reach the server" else "Checking…", style = MaterialTheme.typography.titleLarge)
            if (failed) Text("Retry", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink,
                modifier = Modifier.clip(ShokuShapes.Button).clickable { vm.loadUsage() }.padding(vertical = 8.dp))
            return@OutlinedCard
        }
        Text("${usd(usage.usd)} of \$${formatQty(usage.capUsd)}", style = MaterialTheme.typography.titleLarge)
        Text("${usage.calls} requests. AI stops for the rest of the month at the limit.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(ShokuColors.Tint)) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth((usage.usd / usage.capUsd).toFloat().coerceIn(0f, 1f)).height(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (usage.usd >= usage.capUsd) ShokuColors.Over else ShokuColors.Fill),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Monthly limit", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0, 20.0, 30.0, 50.0).forEach { cap ->
                androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { vm.setCap(cap) }) {
                    app.shoku.ui.components.StatusChip("\$" + formatQty(cap), if (usage.capUsd == cap) app.shoku.ui.components.ChipStyle.Filled else app.shoku.ui.components.ChipStyle.Tinted)
                }
            }
        }
    }
}
