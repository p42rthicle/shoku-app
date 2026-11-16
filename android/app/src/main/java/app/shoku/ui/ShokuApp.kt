package app.shoku.ui

import app.shoku.ui.theme.enterRise
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shoku.ShokuApplication
import app.shoku.ui.add.AddScreen
import app.shoku.ui.add.AddViewModel
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.ShokuFab
import app.shoku.ui.gallery.GalleryScreen
import app.shoku.ui.guide.GuideScreen
import app.shoku.ui.insights.InsightsScreen
import app.shoku.ui.insights.InsightsViewModel
import app.shoku.ui.library.LibraryScreen
import app.shoku.ui.library.LibraryViewModel
import app.shoku.ui.settings.SettingsScreen
import app.shoku.ui.settings.SettingsViewModel
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.today.TodayScreen
import app.shoku.ui.today.TodayViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Outlined.WbSunny),
    Plan("Plan", Icons.Outlined.EventNote),
    Library("Library", Icons.AutoMirrored.Outlined.MenuBook),
    Insights("Insights", Icons.Outlined.Insights),
    Settings("Settings", Icons.Outlined.Settings),
}

private data class AddTarget(
    val date: LocalDate,
    val meal: String,
    val describe: Boolean = false,
    val barcode: String? = null,
    val plate: Boolean = false,
    val scanner: Boolean = false,
    /** Just cooked from Add: open its amount sheet. */
    val batchId: String? = null,
    /** Search text to start with. */
    val query: String? = null,
)

@Composable
fun ShokuApp() {
    val context = LocalContext.current
    val repo = (context.applicationContext as ShokuApplication).repository
    val todayVm: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(repo, context.getSharedPreferences("fridge", android.content.Context.MODE_PRIVATE), (context.applicationContext as ShokuApplication).home) } })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(repo, (context.applicationContext as ShokuApplication).sync) } })
    val planVm: app.shoku.ui.plan.PlanViewModel = viewModel(factory = viewModelFactory { initializer { app.shoku.ui.plan.PlanViewModel(repo, (context.applicationContext as ShokuApplication).home, (context.applicationContext as ShokuApplication).reference, context.getSharedPreferences("shopping", android.content.Context.MODE_PRIVATE)) } })
    val libraryVm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(repo, (context.applicationContext as ShokuApplication).reference) } })
    val insightsVm: InsightsViewModel = viewModel(factory = viewModelFactory { initializer { InsightsViewModel(repo, (context.applicationContext as ShokuApplication).home) } })
    val scope = rememberCoroutineScope()
    val library by remember { repo.library.stateIn(scope, SharingStarted.Eagerly, null) }.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    // Tabs visited before this one (most recent last). Today is home: arriving there clears it, and Back
    // from any other tab returns along this list, ending at Today; only Today exits the app.
    var history by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun go(t: Tab) {
        if (t == tab) return
        history = if (t == Tab.Today) emptyList() else (history - tab.name - t.name + tab.name).filter { it != Tab.Today.name }.takeLast(4)
        tab = t
    }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var showDesign by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Add to stock from Today's + (Add › "Add to stock instead"): the stock sheets, shown over Today.
    val homeStockUi = remember { app.shoku.ui.plan.StockUi() }
    // The search's query and scroll survive a trip to a result and back.
    val searchUi = remember { app.shoku.ui.search.SearchUi() }
    fun openSearch() { app.shoku.ui.search.SearchReturn.action = null; searchUi.reset(); searchOpen = true }
    // Coming back after a while starts on today, not on a day looked at earlier.
    var leftAt by remember { mutableStateOf(0L) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { leftAt = System.currentTimeMillis() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        if (leftAt > 0 && System.currentTimeMillis() - leftAt > 2 * 60_000) todayVm.setDate(LocalDate.now())
    }
    val launch by app.shoku.LaunchRequests.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(launch) {
        val r = launch ?: return@LaunchedEffect
        app.shoku.LaunchRequests.pending.value = null
        showDesign = false; showGuide = false
        // A link or notification starts fresh: its screen, with Back leading to Today.
        history = emptyList()
        val now = LocalDate.now()
        when (r.open) {
            "add", "describe", "plate", "scan" -> {
                tab = Tab.Today
                addTarget = AddTarget(
                    now, r.meal ?: mealForNow(), describe = r.open == "describe", plate = r.open == "plate",
                    barcode = r.barcode, scanner = r.open == "scan" && r.barcode == null,
                )
            }
            "plan" -> if (r.arg == "stock") {
                // Stock moved to Library; old links (and the Sunday notification) still work.
                addTarget = null; libraryVm.openStock(); tab = Tab.Library
            } else {
                addTarget = null; tab = Tab.Plan
                planVm.section = when (r.arg) { "supplements" -> 1; "stock" -> 2; else -> 0 }
                planVm.menuOpen = r.arg == "menu"
            }
            "stock" -> { addTarget = null; libraryVm.openStock(); tab = Tab.Library }
            "search" -> { addTarget = null; openSearch() }
            "library" -> { addTarget = null; tab = Tab.Library; libraryVm.backToList(); r.arg?.let { libraryVm.query = it } }
            "insights" -> { addTarget = null; tab = Tab.Insights }
            "cook" -> { addTarget = null; libraryVm.startCook(); tab = Tab.Library }
            "settings" -> { addTarget = null; tab = Tab.Settings }
            "guide" -> { addTarget = null; tab = Tab.Settings; showGuide = true }
            else -> {
                addTarget = null; tab = Tab.Today
                todayVm.setDate(r.arg?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }

    fun toast(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = repo.exportBackup()
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            }.onSuccess { toast("Backup saved") }.onFailure { toast("Export failed: ${it.message}") }
        }
    }
    val startExport = { exportLauncher.launch("shoku_backup_${LocalDate.now()}.zip") }

    // Back, outermost first (screens opened on top of a tab handle their own Back and win over this):
    // another date on Today goes to today; another tab goes back along the history, then to Today.
    val todayDate = todayVm.state.collectAsState().value.date
    BackHandler(enabled = addTarget == null && (tab != Tab.Today || todayDate != LocalDate.now())) {
        when {
            tab == Tab.Today -> todayVm.setDate(LocalDate.now())
            history.isNotEmpty() -> { tab = Tab.valueOf(history.last()); history = history.dropLast(1) }
            else -> tab = Tab.Today
        }
    }
    BackHandler(enabled = showDesign || showGuide) { showDesign = false; showGuide = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ShokuColors.Paper,
            topBar = {
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(ShokuColors.Paper))
            },
            bottomBar = {
                Column {
                    Hairline()
                    NavigationBar(containerColor = ShokuColors.Paper, tonalElevation = 0.dp) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = {
                                    app.shoku.ui.search.SearchReturn.action = null // you went somewhere yourself
                                    if (t == Tab.Library && tab == Tab.Library) libraryVm.backToList()
                                    go(t)
                                    showDesign = false
                                    showGuide = false
                                },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ShokuColors.Ink,
                                    selectedTextColor = ShokuColors.Ink,
                                    indicatorColor = ShokuColors.Tint,
                                    unselectedIconColor = ShokuColors.Muted,
                                    unselectedTextColor = ShokuColors.Muted,
                                ),
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab == Tab.Today) {
                    val date = todayVm.state.collectAsState().value.date
                    ShokuFab(onClick = { addTarget = AddTarget(date, mealForNow(date)) })
                }
            },
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, shape = ShokuShapes.Button, containerColor = ShokuColors.Fill, contentColor = ShokuColors.Paper, actionColor = ShokuColors.Paper) } },
        ) { padding ->
            // Switching tabs: the new screen fades in with a small lift (springs; "Remove animations" turns it off).
            androidx.compose.animation.AnimatedContent(
                targetState = Triple(tab, showDesign, showGuide),
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(app.shoku.ui.theme.ShokuMotion.effects()) +
                        androidx.compose.animation.slideInVertically(app.shoku.ui.theme.ShokuMotion.spatial()) { it / 40 })
                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)))
                },
                label = "tabs",
            ) { (shownTab, shownDesign, shownGuide) ->
            when {
                shownDesign -> GalleryScreen(padding)
                shownGuide -> GuideScreen(padding, onBack = { showGuide = false })
                shownTab == Tab.Today -> TodayScreen(
                    vm = todayVm,
                    library = library,
                    snackbar = snackbar,
                    contentPadding = padding,
                    onAdd = { d, meal -> addTarget = AddTarget(d, meal ?: mealForNow(d)) },
                    onCook = {
                        val day = todayVm.state.value.date
                        libraryVm.afterCook = { id -> go(Tab.Today); if (id != null) addTarget = AddTarget(day, mealForNow(day), batchId = id) }
                        libraryVm.startCook(); go(Tab.Library)
                    },
                    onLogBatch = { d, id -> addTarget = AddTarget(d, mealForNow(d), batchId = id) },
                    onSearch = { openSearch() },
                    onOpenSupplements = { planVm.menuOpen = false; planVm.section = 1; go(Tab.Plan) },
                    onOpenItem = { type, id ->
                        libraryVm.backToList()
                        libraryVm.open(if (type == app.shoku.data.db.RefType.DISH) app.shoku.ui.library.LibRoute.DishDetail(id) else app.shoku.ui.library.LibRoute.BatchDetail(id))
                        libraryVm.returnTo = { go(Tab.Today) }
                        go(Tab.Library)
                    },
                )
                shownTab == Tab.Plan -> app.shoku.ui.plan.PlanScreen(planVm, padding)
                shownTab == Tab.Library -> LibraryScreen(libraryVm, snackbar, padding, planVm)
                shownTab == Tab.Insights -> InsightsScreen(insightsVm, padding, onOpenDay = { d -> todayVm.setDate(d); go(Tab.Today) })
                else -> SettingsScreen(settingsVm, padding, onExport = startExport, onOpenDesign = { showDesign = true }, onOpenGuide = { showGuide = true })
            }
            }
        }

        if (searchOpen) {
            // Every result closes search and opens where the thing lives; closing that comes back here.
            fun leave(then: () -> Unit) {
                val from = tab
                app.shoku.ui.search.SearchReturn.action = { go(from); searchOpen = true }
                searchOpen = false
                then()
            }
            fun leaveForGood(then: () -> Unit) { app.shoku.ui.search.SearchReturn.action = null; searchOpen = false; then() }
            val backToSearchOr: (() -> Unit) -> () -> Unit = { fallback -> { if (!app.shoku.ui.search.SearchReturn.go()) fallback() } }
            Box(Modifier.fillMaxSize().enterRise()) { app.shoku.ui.search.GlobalSearchScreen(
                library, planVm,
                app.shoku.ui.search.SearchActions(
                    openDay = { d -> leaveForGood { todayVm.setDate(d); go(Tab.Today) } },
                    openFood = { id -> leave { libraryVm.backToList(); libraryVm.tab = app.shoku.ui.library.LibTab.Foods; libraryVm.editFoodRequest = id; go(Tab.Library) } },
                    openDish = { id -> leave { libraryVm.backToList(); libraryVm.open(app.shoku.ui.library.LibRoute.DishDetail(id)); libraryVm.returnTo = backToSearchOr { go(Tab.Today) }; go(Tab.Library) } },
                    openBatch = { id -> leave { libraryVm.backToList(); libraryVm.open(app.shoku.ui.library.LibRoute.BatchDetail(id)); libraryVm.returnTo = backToSearchOr { go(Tab.Today) }; go(Tab.Library) } },
                    openStock = { id -> leave { libraryVm.openStock(); planVm.openStockRequest = id; go(Tab.Library) } },
                    openTemplate = { id -> leaveForGood { planVm.menuOpen = false; planVm.section = 0; planVm.editTemplateRequest = id; go(Tab.Plan) } },
                    openSupplement = { id -> leaveForGood { planVm.menuOpen = false; planVm.section = 1; planVm.editSupplementRequest = id; go(Tab.Plan) } },
                    addReference = { r -> leave { libraryVm.addReference(r) { f -> libraryVm.backToList(); libraryVm.tab = app.shoku.ui.library.LibTab.Foods; libraryVm.editFoodRequest = f.id; go(Tab.Library) } } },
                    addOnline = { p -> leave { libraryVm.addOnline(p) { f -> libraryVm.backToList(); libraryVm.tab = app.shoku.ui.library.LibTab.Foods; libraryVm.editFoodRequest = f.id; go(Tab.Library) } } },
                    log = { text -> leaveForGood { addTarget = AddTarget(LocalDate.now(), mealForNow(), query = text) } },
                ),
                onClose = { app.shoku.ui.search.SearchReturn.action = null; searchOpen = false },
                ui = searchUi,
            ) }
        }

        // Library has its own stock sheets; elsewhere these show the ones started from Add.
        if (tab != Tab.Library) app.shoku.ui.plan.StockSheets(planVm, homeStockUi)

        addTarget?.let { target ->
            val addVm: AddViewModel = viewModel(
                key = "add-${target.date}-${target.meal}-${System.identityHashCode(target)}",
                factory = viewModelFactory { initializer { AddViewModel(repo, (context.applicationContext as ShokuApplication).reference, target.date, target.meal) } },
            )
            Box(Modifier.fillMaxSize().enterRise()) {
                AddScreen(
                    addVm, snackbar, onClose = { addTarget = null }, startDescribing = target.describe, startBarcode = target.barcode,
                    startPlate = target.plate, startScanner = target.scanner, startBatchId = target.batchId, startQuery = target.query,
                    onAddToStock = { addTarget = null; homeStockUi.searching = true },
                    onStockFood = { id -> addTarget = null; planVm.startAmountFor(id) },
                    onCook = { name ->
                        // Cook in Library, then come back to this Add with the new batch ready to log.
                        val back = target.copy(describe = false, barcode = null, plate = false, scanner = false, batchId = null)
                        libraryVm.afterCook = { id -> addTarget = back.copy(batchId = id); go(Tab.Today) }
                        addTarget = null
                        if (name.isNullOrBlank()) libraryVm.startCook() else libraryVm.startNewDish(name)
                        go(Tab.Library)
                    },
                )
                SnackbarHost(
                    snackbar,
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
                ) { Snackbar(it, shape = ShokuShapes.Button, containerColor = ShokuColors.Fill, contentColor = ShokuColors.Paper, actionColor = ShokuColors.Paper) }
            }
        }
    }
}
