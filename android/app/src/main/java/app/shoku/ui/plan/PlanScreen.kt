package app.shoku.ui.plan

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.HomeRepository
import app.shoku.data.ShokuRepository
import app.shoku.data.SupplementState
import app.shoku.data.db.Supplement
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.Segmented
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import app.shoku.domain.inFoodUnit
import app.shoku.domain.shoppingList
import app.shoku.domain.stockLevels
import app.shoku.domain.weekShopping
import app.shoku.data.reference.toLibraryFood
import app.shoku.ui.today.resolvePick
import java.time.LocalDate

class PlanViewModel(
    val repo: ShokuRepository,
    val home: HomeRepository,
    private val reference: app.shoku.data.reference.ReferenceData,
    private val prefs: android.content.SharedPreferences? = null,
) : ViewModel() {
    /** Shopping rows swiped away (name|reason), kept on the phone. */
    private val dismissed = kotlinx.coroutines.flow.MutableStateFlow(prefs?.getStringSet("dismissed", emptySet()).orEmpty())
    fun dismiss(s: app.shoku.domain.ShoppingItem) = save(dismissed.value + s.key)
    fun showHidden() = save(emptySet())
    private fun save(set: Set<String>) { dismissed.value = set; prefs?.edit()?.putStringSet("dismissed", set)?.apply() }
    private val ai = app.shoku.data.ai.AiClient()
    /** Which section is showing: 0 meals, 1 supplements, 2 stock. */
    var section by androidx.compose.runtime.mutableIntStateOf(0)

    // ---------- Meal menu ----------

    var menuOpen by androidx.compose.runtime.mutableStateOf(false)
    var menuMeal by androidx.compose.runtime.mutableStateOf(app.shoku.data.db.Meals.LUNCH)
    val logs: StateFlow<List<app.shoku.data.db.LogEntry>> = repo.allEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Last AI ideas, for which meal. */
    val ideas = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, app.shoku.data.ai.PlanProposal>?>(null)
    val ideasWorking = kotlinx.coroutines.flow.MutableStateFlow(false)
    var ideasError by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var ideasJob: kotlinx.coroutines.Job? = null

    fun setDay(weekday: Int, meal: String, templateId: String?) = viewModelScope.launch { home.setDay(weekday, meal, templateId) }

    fun suggest(meal: String) {
        ideasError = null
        ideasJob = viewModelScope.launch {
            ideasWorking.value = true
            runCatching {
                val lib = repo.library.first()
                val goal = app.shoku.domain.goalFor(repo.allGoals.first(), LocalDate.now().toString())
                app.shoku.data.ai.PlanAi(ai).suggestMeal(
                    lib, home.plan.first(), stockView.value.levels, repo.allEntries.first(),
                    goal?.kcal ?: 1800.0, goal?.protein ?: 110.0,
                    mealRules(meal, goal?.kcal ?: 1800.0, goal?.protein ?: 110.0), meal,
                )
            }.onSuccess { ideas.value = meal to it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) ideasError = it.message ?: "AI request failed" }
            ideasWorking.value = false
        }
    }

    fun cancelIdeas() { ideasJob?.cancel(); ideasWorking.value = false }

    // ---------- Long lists: which are opened with "See all" ----------

    var openLists by androidx.compose.runtime.mutableStateOf(emptySet<String>())

    // ---------- Opened from elsewhere (global search) ----------
    var openStockRequest by androidx.compose.runtime.mutableStateOf<String?>(null)
    var editTemplateRequest by androidx.compose.runtime.mutableStateOf<String?>(null)
    var editSupplementRequest by androidx.compose.runtime.mutableStateOf<String?>(null)
    fun toggleList(key: String) { openLists = if (key in openLists) openLists - key else openLists + key }

    // ---------- Plan my week (AI) ----------

    var weekSheet by androidx.compose.runtime.mutableStateOf(false)
    val week = kotlinx.coroutines.flow.MutableStateFlow<app.shoku.data.ai.PlanProposal?>(null)
    val weekWorking = kotlinx.coroutines.flow.MutableStateFlow(false)
    var weekError by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var weekJob: kotlinx.coroutines.Job? = null

    fun planWeek(wish: String?) {
        weekError = null
        weekJob = viewModelScope.launch {
            weekWorking.value = true
            runCatching {
                val goal = app.shoku.domain.goalFor(repo.allGoals.first(), LocalDate.now().toString())
                val kcal = goal?.kcal ?: 1800.0
                val protein = goal?.protein ?: 110.0
                app.shoku.data.ai.PlanAi(ai).propose(
                    repo.library.first(), home.plan.first(), stockView.value.levels, kcal, protein,
                    dayRules(kcal, protein), wish?.takeIf { it.isNotBlank() }, repo.allEntries.first(),
                )
            }.onSuccess { week.value = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) weekError = it.message ?: "AI request failed" }
            weekWorking.value = false
        }
    }

    fun cancelWeek() { weekJob?.cancel(); weekWorking.value = false }

    /** Saves the proposal's templates and puts them on their days (other templates stay in the menu). */
    fun useWeek(p: app.shoku.data.ai.PlanProposal) = viewModelScope.launch {
        val start = home.plan.first().templates.size
        p.drafts.forEachIndexed { i, d ->
            val t = d.template.copy(position = start + i)
            home.saveTemplate(t, d.items)
            home.setWeekdays(t, d.weekdays)
        }
        week.value = null
        weekSheet = false
    }

    /** Saves an AI idea as a template (no days yet: pick them with the day chips). */
    fun saveDraft(d: app.shoku.data.ai.DraftTemplate) = viewModelScope.launch {
        home.saveTemplate(d.template.copy(position = home.plan.first().templates.size), d.items)
        ideas.value = ideas.value?.let { (m, p) -> m to p.copy(drafts = p.drafts - d) }
    }

    fun saveHistory(h: app.shoku.domain.HistoryMeal) = viewModelScope.launch {
        val id = java.util.UUID.randomUUID().toString()
        val items = h.items.mapIndexed { i, it ->
            app.shoku.data.db.TemplateItem(java.util.UUID.randomUUID().toString(), id, it.refType, it.refId,
                if (it.refType == app.shoku.domain.SLOT) "Sabzi or dal (batch in the fridge)" else "", it.unit, it.qty, i)
        }
        home.saveTemplate(app.shoku.data.db.MealTemplate(id, h.name.removePrefix("Usual: "), h.meal, home.plan.first().templates.size), items)
    }

    // ---------- Stock ----------

    val stockView: StateFlow<StockView> = kotlinx.coroutines.flow.combine(
        repo.library, repo.allEntries, home.stock, kotlinx.coroutines.flow.combine(home.supplements, home.plan, ::Pair), dismissed,
    ) { lib, logs, stock, (supps, plan), hidden ->
        val levels = lib.stockLevels(stock.first, stock.second, logs, supps.doses, plan, LocalDate.now())
        val all = lib.shoppingList(levels, plan)
        val nextMonday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val units = levels.mapNotNull { it.item.foodId }.distinct().mapNotNull { id ->
            val f = lib.foods[id] ?: return@mapNotNull null
            id to (listOf(f.unit to 1.0) + lib.portionsOf(f).filter { it.label != f.unit && it.qty > 0 }.map { it.label to it.qty }).distinctBy { it.first }
        }.toMap()
        StockView(
            levels, all.filter { it.key !in hidden }, hiddenCount = all.count { it.key in hidden },
            week = lib.weekShopping(plan, levels, nextMonday), weekFrom = nextMonday, units = units,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StockView())

    val busy = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A food picked or scanned, ready for "how much?". Second value: amount to suggest in the food's unit. */
    val amountFor = kotlinx.coroutines.flow.MutableStateFlow<Pair<app.shoku.data.db.Food, Double>?>(null)

    private suspend fun libraryWith(foodId: String) = repo.library.first { it.foods.containsKey(foodId) }

    fun pickForStock(pick: app.shoku.ui.today.ReplacePick) = viewModelScope.launch {
        val (type, id) = repo.resolvePick(pick)
        if (type != app.shoku.data.db.RefType.FOOD) { busy.value = "Only foods can go in stock"; return@launch }
        val food = libraryWith(id).foods.getValue(id)
        val pack = (pick as? app.shoku.ui.today.ReplacePick.Online)?.product?.packQty
        amountFor.value = food to (pack ?: if (food.unit == "pc") 1.0 else food.baseQty)
    }

    /** A food just scanned or picked for stock: ask how much, starting at its pack size if known. */
    fun startAmount(food: app.shoku.data.db.Food) = viewModelScope.launch {
        val pack = libraryWith(food.id).portionsOf(food).firstOrNull { it.label == "pack" }?.qty
        amountFor.value = food to (pack ?: if (food.unit == "pc") 1.0 else food.baseQty)
    }

    /** "2 kg chana dal, 30 eggs, 1 l milk" -> stock, through the AI (one request, about a cent). */
    /** True while "Say it" waits for the AI (drives the loading overlay). */
    val sayWorking = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var sayJob: kotlinx.coroutines.Job? = null
    fun cancelSay() { sayJob?.cancel(); sayWorking.value = false; busy.value = null }

    fun sayStock(text: String) { sayJob = viewModelScope.launch { sayStockNow(text) } }

    private suspend fun sayStockNow(text: String) {
        sayWorking.value = true
        busy.value = null
        runCatching {
            val lib = repo.library.first()
            val keys = ai.libraryKeys(lib, extra = ai.referenceCandidates(text, reference, lib))
            val items = ai.resolve(ai.meal(text, keys).items, keys, lib, byEye = false)
            var added = 0
            for (it in items) {
                val (foodId, unit, qty) = when {
                    it.reference != null -> Triple(repo.adoptReference(it.reference), it.unit, it.qty)
                    it.refType == app.shoku.data.db.RefType.FOOD && it.refId != null -> Triple(it.refId, it.unit, it.qty)
                    it.refType == app.shoku.data.db.RefType.QUICK && it.per100 != null -> {
                        val f = app.shoku.data.db.Food(
                            ShokuRepository.newId(), it.name, "g", 100.0, it.per100.kcal, it.per100.protein, it.per100.fibre, it.per100.carbs, it.per100.fat,
                            source = "ai-estimate",
                        )
                        repo.saveFood(f, updatePast = false)
                        Triple(f.id, "g", it.qty)
                    }
                    else -> continue
                }
                val l = libraryWith(foodId)
                val amount = l.inFoodUnit(foodId, unit, qty) ?: continue
                home.addFoodStock(l.foods.getValue(foodId), amount)
                added++
            }
            "Added $added ${if (added == 1) "item" else "items"} to stock. Check the amounts below."
        }.onSuccess { busy.value = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) busy.value = it.message ?: "AI request failed" }
        sayWorking.value = false
    }

    fun addStock(food: app.shoku.data.db.Food, unit: String, qty: Double) = viewModelScope.launch {
        val l = libraryWith(food.id)
        val amount = l.inFoodUnit(food.id, unit, qty) ?: return@launch
        home.addFoodStock(food, amount)
        amountFor.value = null
    }

    fun bought(s: app.shoku.domain.ShoppingItem) = viewModelScope.launch {
        val item = s.item
        if (item != null && !item.tracked) { home.saveStockItem(item.copy(status = "have")); return@launch }
        val id = s.foodId ?: return@launch
        val food = repo.library.first().foods[id] ?: return@launch
        amountFor.value = food to (if (food.unit == "pc") 1.0 else food.baseQty)
    }

    /** Bought from the weekly list: opens the amount sheet at the amount to buy. */
    fun boughtWeek(w: app.shoku.domain.WeekItem) = viewModelScope.launch {
        val food = repo.library.first().foods[w.foodId] ?: return@launch
        // The amount sheet works in the food's own unit: convert a portion count back (7 small apples -> g).
        amountFor.value = food to (w.portion?.let { w.buyRounded * it.second } ?: w.buyRounded)
    }

    fun saveStock(item: app.shoku.data.db.StockItem, newCount: Double?) = viewModelScope.launch {
        home.saveStockItem(item)
        if (newCount != null) home.recount(item, newCount)
    }

    fun deleteStock(item: app.shoku.data.db.StockItem) = viewModelScope.launch { home.deleteStockItem(item) }
    fun setStatus(item: app.shoku.data.db.StockItem, status: String) = viewModelScope.launch { home.saveStockItem(item.copy(status = status)) }

    val supplements: StateFlow<SupplementState> = home.supplements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupplementState(emptyList(), emptyList(), emptyList(), emptyList()))

    val plan: StateFlow<app.shoku.domain.PlanState> = home.plan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.shoku.domain.PlanState.EMPTY)
    val library: StateFlow<app.shoku.domain.Library?> = repo.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val goals = repo.allGoals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveTemplate(t: app.shoku.data.db.MealTemplate, items: List<app.shoku.data.db.TemplateItem>, days: Set<Int>) = viewModelScope.launch {
        home.saveTemplate(t, items)
        home.setWeekdays(t, days)
    }

    fun deleteTemplate(t: app.shoku.data.db.MealTemplate) = viewModelScope.launch { home.deleteTemplate(t) }

    fun toggleToday(s: Supplement, taken: Boolean) = viewModelScope.launch { home.setTaken(s, LocalDate.now(), !taken) }

    fun saveSupplement(s: Supplement, newCount: Double?) = viewModelScope.launch {
        home.saveSupplement(s)
        if (newCount != null) home.countSupplement(s, newCount)
    }
}

/** Meals plan, supplements and home stock. */
@Composable
fun PlanScreen(vm: PlanViewModel, contentPadding: PaddingValues) {
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(vm.section) }
    // A link (shoku://plan/stock) sets the section on the view model.
    androidx.compose.runtime.LaunchedEffect(vm.section) { section = vm.section }
    val supps by vm.supplements.collectAsState()
    val plan by vm.plan.collectAsState()
    val library by vm.library.collectAsState()
    val goals by vm.goals.collectAsState()
    val goal = app.shoku.domain.goalFor(goals, LocalDate.now().toString())
    var editing by remember { mutableStateOf<Supplement?>(null) }
    var editingRules by remember { mutableStateOf(false) }
    if (editingRules) FoodRulesSheet(onDismiss = { editingRules = false })
    androidx.compose.runtime.LaunchedEffect(vm.editSupplementRequest, supps) {
        val id = vm.editSupplementRequest ?: return@LaunchedEffect
        supps.supplements.firstOrNull { it.id == id }?.let { editing = it; vm.editSupplementRequest = null }
    }
    // null = closed; Pair(template or null for new)
    var editingTemplate by remember { mutableStateOf<Pair<app.shoku.data.db.MealTemplate?, Unit>?>(null) }
    androidx.compose.runtime.LaunchedEffect(vm.editTemplateRequest, plan) {
        val id = vm.editTemplateRequest ?: return@LaunchedEffect
        plan.templates.firstOrNull { it.id == id && !it.deleted }?.let { editingTemplate = it to Unit; vm.editTemplateRequest = null }
    }

    val lib0 = library
    if (vm.menuOpen && lib0 != null) {
        MealMenu(vm, lib0, contentPadding, onEdit = { editingTemplate = it to Unit })
    } else LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            ScreenTitle("Plan")
            Spacer(Modifier.height(14.dp))
            Segmented(listOf("Meals", "Supplements"), section.coerceAtMost(1)) { section = it; vm.section = it }
        }
        when (section) {
            0 -> {
                item(key = "menu-open") {
                    app.shoku.ui.components.PrimaryButton("Meal menu: switch, mix and AI ideas", onClick = { vm.menuOpen = true })
                    Spacer(Modifier.height(10.dp))
                    app.shoku.ui.components.SecondaryButton("Plan my week with AI", onClick = { vm.weekSheet = true })
                    Spacer(Modifier.height(10.dp))
                    val rules = app.shoku.data.DietNotes.lines.size
                    app.shoku.ui.components.SecondaryButton(if (rules == 0) "Food rules for AI" else "Food rules for AI · $rules", onClick = { editingRules = true })
                }
                mealsSection(plan, library, goal?.kcal, goal?.protein, onEdit = { editingTemplate = it to Unit }, open = vm.openLists, onToggle = vm::toggleList)
            }
            else -> supplementsSection(
                supps, onToggleToday = vm::toggleToday, onEdit = { editing = it }, open = vm.openLists, onToggle = vm::toggleList,
                onNew = { editing = Supplement(java.util.UUID.randomUUID().toString(), "", startDate = LocalDate.now().toString(), position = supps.supplements.size, slot = app.shoku.data.db.Meals.BREAKFAST) },
            )
        }
    }

    if (vm.weekSheet && library != null) WeekPlanSheet(vm, library!!)
    editingTemplate?.let { (t, _) ->
        val lib = library ?: return@let
        TemplateEditor(
            t, plan, lib,
            onSave = { tpl, items, days -> vm.saveTemplate(tpl, items, days); editingTemplate = null },
            onDelete = { tpl -> vm.deleteTemplate(tpl); editingTemplate = null },
            onDismiss = { editingTemplate = null },
        )
    }
    editing?.let { s ->
        SupplementEditor(
            s, supps, onSave = { updated, count -> vm.saveSupplement(updated, count); editing = null }, onDismiss = { editing = null },
            isNew = supps.supplements.none { it.id == s.id },
            onDelete = { vm.saveSupplement(it.copy(deleted = true), null); editing = null },
        )
    }
}

/** Rules sent with AI meal ideas: this meal's share of the day's goals, plus the user's own food rules. */
private fun mealRules(meal: String, kcal: Double, protein: Double): List<String> {
    val share = when (meal) {
        app.shoku.data.db.Meals.BREAKFAST -> 0.25
        app.shoku.data.db.Meals.LUNCH -> 0.30
        app.shoku.data.db.Meals.SNACKS -> 0.15
        else -> 0.30
    }
    return listOf("this $meal: about ${Math.round(kcal * share / 10) * 10} kcal and at least ${Math.round(protein * share)} g protein (stay within 10%)") +
        app.shoku.data.DietNotes.lines
}

/** Rules for a whole week: daily goals plus the user's own food rules. */
private fun dayRules(kcal: Double, protein: Double): List<String> =
    listOf("every day about ${Math.round(kcal / 10) * 10} kcal and at least ${Math.round(protein)} g protein (within 5%)") + app.shoku.data.DietNotes.lines

/** The user's food rules for AI ideas and week plans: one per line, kept on the phone. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FoodRulesSheet(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(app.shoku.data.DietNotes.text) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper, shape = app.shoku.ui.theme.ShokuShapes.Sheet,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Food rules for AI", style = MaterialTheme.typography.headlineSmall)
            Text("One per line. Sent with AI meal ideas and week plans, never anywhere else. E.g. “no pork or beef”, “low-fat dairy”, “no eggs on Tuesdays”.",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            app.shoku.ui.components.LabeledField("Rules", text, { text = it }, Modifier.fillMaxWidth(), multiLine = true)
            app.shoku.ui.components.PrimaryButton("Save", onClick = { app.shoku.data.DietNotes.text = text; onDismiss() })
        }
    }
}
