package app.shoku

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.shoku.data.ShokuRepository
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.reference.ReferenceData
import app.shoku.data.sync.SyncEngine
import app.shoku.data.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

/**
 * Holds the single database, repository and sync engine. Small enough that manual wiring beats a DI framework.
 * Open only so the screenshot tests can start it with a test WorkManager (see ScreenshotApp).
 */
open class ShokuApplication : Application() {
    val database: ShokuDatabase by lazy { ShokuDatabase.create(this) }
    /** Lives as long as the app process; used for flows shared between screens. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository: ShokuRepository by lazy { ShokuRepository(database, appScope) }
    val sync: SyncEngine by lazy { SyncEngine(this, database) }
    val reference: ReferenceData by lazy { ReferenceData(assets) }
    val home: app.shoku.data.HomeRepository by lazy { app.shoku.data.HomeRepository(database, appScope) }
    val health: app.shoku.data.health.HealthExport by lazy { app.shoku.data.health.HealthExport(this, database) }
    val units: app.shoku.data.ai.UnitsFiller by lazy { app.shoku.data.ai.UnitsFiller(this, repository) }

    override fun onCreate() {
        super.onCreate()
        app.shoku.data.MealSettingsStore.init(this)
        app.shoku.data.DietNotes.init(this)
        app.shoku.data.PhotoStore.init(this)
        app.shoku.data.ai.AiMemory.init(this)
        app.shoku.data.reference.OfflineProducts.init(this)
        app.shoku.data.ai.AiSettings.init(this)
        // One-time grouping into generic foods; idempotent, so a flag only saves the work.
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        appScope.launch {
            // One time: past quick / AI-estimated entries become library foods (searchable, ranked).
            if (!prefs.getBoolean("quickToFoods_v1", false)) {
                runCatching { repository.promoteQuickEntries() }
                    .onSuccess { prefs.edit().putBoolean("quickToFoods_v1", true).apply() }
                    .onFailure { android.util.Log.e("Shoku", "Linking estimates failed", it) }
            }
            // One time: "[o] Chicken Curry (Pak Resto.)" becomes "Chicken Curry", with "Ordered online · Pak Resto."
            // in the notes of its entries.
            if (!prefs.getBoolean("onlineOrders_v1", false)) {
                runCatching { repository.tidyOnlineOrders() }
                    .onSuccess { prefs.edit().putBoolean("onlineOrders_v1", true).apply() }
                    .onFailure { android.util.Log.e("Shoku", "Tidying online orders failed", it) }
            }
            // One time: AI memories become one per food, "AI said 200 g, it was 1 serving (~150 g)".
            runCatching { app.shoku.data.ai.AiMemory.upgrade(repository.weightOf()) }
                .onFailure { android.util.Log.e("Shoku", "Upgrading AI memories failed", it) }
            if (!prefs.getBoolean("foodFamilies_v1", false)) {
                runCatching { repository.applyFoodFamilies() }
                    .onSuccess { prefs.edit().putBoolean("foodFamilies_v1", true).apply() }
                    .onFailure { android.util.Log.e("Shoku", "Grouping foods failed", it) }
            }
        }
        // Keep the home-screen widget in step with the log (and the goal).
        appScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            kotlinx.coroutines.flow.combine(repository.allEntries, repository.allGoals) { _, _ -> Unit }
                .debounce(800)
                .collect { runCatching { app.shoku.ui.widget.TodayWidget.update(this@ShokuApplication) } }
        }
        app.shoku.data.notify.Reminders.ensureChannel(this)
        app.shoku.data.notify.Reminders.scheduleAll(this)
        if (!sync.configured) app.shoku.data.ai.AiSettings.fillUnitsOnStart(appScope) { units.run() }
        if (!sync.configured) return
        SyncWorker.schedulePeriodic(this)
        // Sync when the app opens (to pull) and when it goes to the background (to push what changed).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SyncWorker.syncSoon(this@ShokuApplication, delaySeconds = 2)
                // Household units by AI: sends or collects a half-price batch job, at most once a day.
                appScope.launch { runCatching { units.run() }.onFailure { android.util.Log.w("Shoku", "Units fill", it) } }
                appScope.launch { runCatching { repository.autoFinishBatches() } }
            }
            override fun onStop(owner: LifecycleOwner) {
                SyncWorker.syncSoon(this@ShokuApplication, delaySeconds = 3)
                appScope.launch { runCatching { health.run() }.onFailure { android.util.Log.w("Shoku", "Health Connect export", it) } }
            }
        })
    }
}
