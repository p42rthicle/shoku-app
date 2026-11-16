package app.shoku.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Batch
import app.shoku.data.db.Dish
import app.shoku.data.ai.AiClient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.net.BackendException
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import app.shoku.domain.BatchConsumption
import app.shoku.domain.IngredientRef
import app.shoku.domain.Library
import app.shoku.domain.PortionMetrics
import app.shoku.domain.batchConsumption
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import app.shoku.data.reference.toLibraryFood

data class LibraryData(val library: Library, val entries: List<LogEntry>) {
    private val batchLogs = entries.filter { it.refType == RefType.BATCH }

    fun consumption(b: Batch): Pair<PortionMetrics, BatchConsumption> {
        val m = library.batchMetrics(b)
        return m to batchConsumption(m, b.id, batchLogs)
    }

    /** How many entries use an item, for showing where something is used. */
    fun usageCount(refType: String, refId: String) = entries.count { it.refType == refType && it.refId == refId }
}

internal enum class LibTab { Foods, Dishes, Batches, Stock }

/** Screens inside the Library tab. */
internal sealed interface LibRoute {
    data object List : LibRoute
    data class DishDetail(val id: String) : LibRoute
    data class BatchDetail(val id: String) : LibRoute
    data class Cook(val dishId: String) : LibRoute
}

class LibraryViewModel(private val repo: ShokuRepository, val reference: app.shoku.data.reference.ReferenceData? = null) : ViewModel() {

    // ---------- New foods from the databases and Open Food Facts (search in Library) ----------

    var online by mutableStateOf<List<app.shoku.data.reference.ScannedProduct>>(emptyList())
        private set
    var onlineLoading by mutableStateOf(false)
        private set

    init {
        // Dispatched (not immediate): it reads `query`, which is declared further down and only exists
        // once the constructor has finished. Starting it immediately crashed the app on launch.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            androidx.compose.runtime.snapshotFlow { query.trim() }
                .debounce(600)
                .distinctUntilChanged()
                .collectLatest { q ->
                    if (q.length < 3) { online = emptyList(); onlineLoading = false; return@collectLatest }
                    onlineLoading = true
                    online = runCatching { app.shoku.data.reference.OpenFoodFacts.search(q) }.getOrDefault(emptyList())
                    onlineLoading = false
                }
        }
    }

    /** Copies a database dish into the library and hands back the new food. */
    fun addReference(r: app.shoku.data.reference.ReferenceFood, done: (Food) -> Unit) = viewModelScope.launch {
        val id = repo.adoptReference(r)
        done(r.toFood().copy(id = id))
    }

    /** Saves an online product (with its serving/pack portions) to the library. */
    fun addOnline(p: app.shoku.data.reference.ScannedProduct, done: (Food) -> Unit) = viewModelScope.launch {
        val (food, portions) = p.toLibraryFood()
        repo.saveFood(food, updatePast = false)
        if (portions.isNotEmpty()) repo.savePortions(portions)
        done(food)
    }

    // UI state kept here (not in the composables) so it survives opening a dish and coming back.
    internal var tab by mutableStateOf(LibTab.Foods)
    internal var query by mutableStateOf("")
    val listState = LazyListState()
    internal var stack by mutableStateOf(listOf<LibRoute>(LibRoute.List))
        private set

    internal fun open(route: LibRoute) { stack = stack + route }
    internal fun back() {
        if (stack.size == 2 && returnTo != null) { val f = returnTo; returnTo = null; stack = listOf(LibRoute.List); f?.invoke(); return }
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    /** Set when a dish or batch was opened from elsewhere (Today, search): Back from it goes back there. */
    var returnTo: (() -> Unit)? = null
    internal fun replaceTop(route: LibRoute) { stack = stack.dropLast(1) + route }
    /** Tapping the Library tab again goes back to the list. */
    fun backToList() { stack = listOf(LibRoute.List); returnTo = null }

    /** Shows the "which dish are you cooking?" picker (from Batches +, Today or shoku://cook). */
    internal var pickingCook by mutableStateOf(false)
    fun startCook() { backToList(); tab = LibTab.Batches; pickingCook = true }

    /** Set by a screen that started cooking (Add): gets the new batch's id, or null if cooking was left. */
    var afterCook: ((String?) -> Unit)? = null
    /** A new dish to create and cook straight away (name from a search). */
    var newDishRequest by mutableStateOf<String?>(null)
    /** A food to open in the editor (from global search). */
    var editFoodRequest by mutableStateOf<String?>(null)
    fun openStock() { backToList(); tab = LibTab.Stock; query = "" }
    fun startNewDish(name: String) { backToList(); tab = LibTab.Batches; newDishRequest = name }
    fun finishCook(batchId: String?) { val f = afterCook; afterCook = null; f?.invoke(batchId) }

    val data: StateFlow<LibraryData?> = combine(repo.library, repo.allEntries) { l, e -> LibraryData(l, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveFood(food: Food, updatePast: Boolean, done: (Int) -> Unit = {}) = viewModelScope.launch { done(repo.saveFood(food, updatePast)) }
    fun savePortions(rows: List<app.shoku.data.db.FoodPortion>) = viewModelScope.launch { repo.savePortions(rows) }
    fun archiveFood(food: Food) = viewModelScope.launch { repo.setFoodArchived(food, true) }
    fun deleteFood(food: Food) = viewModelScope.launch { repo.deleteFood(food) }
    fun unarchiveFood(food: Food) = viewModelScope.launch { repo.setFoodArchived(food, false) }
    var showAllFoods by mutableStateOf(false)
    var showArchivedFoods by mutableStateOf(false)

    fun saveDish(dish: Dish, ingredients: List<IngredientRef>, updatePast: Boolean = false, done: (Int) -> Unit = {}) =
        viewModelScope.launch { done(repo.saveDish(dish, ingredients, updatePast)) }
    fun duplicateDish(dish: Dish, done: (String) -> Unit) = viewModelScope.launch { done(repo.duplicateDish(dish)) }
    fun setDishArchived(dish: Dish, archived: Boolean) = viewModelScope.launch { repo.setDishArchived(dish, archived) }

    fun saveBatch(batch: Batch, ingredients: List<IngredientRef>, done: (String) -> Unit = {}) = viewModelScope.launch { done(repo.saveBatch(batch, ingredients)) }
    fun setBatchFinished(batch: Batch, finished: Boolean) = viewModelScope.launch { repo.setBatchFinished(batch, finished) }
    fun deleteBatch(batch: Batch) = viewModelScope.launch { repo.deleteBatch(batch) }

    private val aiClient = AiClient()

    /**
     * "250 g rajma, 2 onions, 2 tbsp oil..." -> ingredient rows. Items the library doesn't have are
     * added as new foods from the AI's estimate (source "ai-estimate") so they can be checked later.
     */
    fun aiRecipe(library: Library, text: String, done: (ingredients: List<IngredientRef>, servings: Double?, newFoods: List<String>) -> Unit, fail: (String) -> Unit) =
        viewModelScope.launch {
            runCatching {
                // Recipes stay with library foods: batch ingredients must be real library rows.
                val keys = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { aiClient.libraryKeys(library) }
                keys to aiClient.recipe(text, keys)
            }.onSuccess { (keys, r) ->
                val refs = mutableListOf<IngredientRef>()
                val created = mutableListOf<String>()
                for (item in r.ingredients) {
                    val ref = item.matchKey?.let { keys.refs[it] }
                    val units = ref?.let { (t, id) -> library.unitsFor(t, id) }.orEmpty()
                    if (ref != null && units.isNotEmpty()) {
                        refs += IngredientRef(ref.first, ref.second, item.unit.takeIf { it in units } ?: units.first(), item.qty)
                    } else item.estimate?.let { e ->
                        val food = Food(
                            id = ShokuRepository.newId(), name = item.name, unit = "g", baseQty = 100.0,
                            kcal = e.kcal, protein = e.protein, fibre = e.fibre, carbs = e.carbs, fat = e.fat,
                            kind = FoodKind.GENERIC, source = "ai-estimate",
                        )
                        repo.saveFood(food, updatePast = false)
                        refs += IngredientRef(RefType.FOOD, food.id, "g", e.grams)
                        created += item.name
                    }
                }
                done(refs, r.servings, created)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                fail(if (e is BackendException) e.message ?: "AI request failed" else e.message ?: "No connection to the server.")
            }
        }
}
