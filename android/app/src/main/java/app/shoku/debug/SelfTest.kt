package app.shoku.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.shoku.ShokuApplication
import app.shoku.data.DietNotes
import app.shoku.data.db.Batch
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Food
import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import app.shoku.data.db.RefType
import app.shoku.data.db.Supplement
import app.shoku.data.notify.ReminderWorker
import app.shoku.data.notify.Reminders
import app.shoku.data.reference.OpenFoodFacts
import app.shoku.data.reference.toLibraryFood
import app.shoku.domain.IngredientRef
import app.shoku.domain.ScheduleRule
import app.shoku.domain.encodeSchedule
import app.shoku.domain.shoppingList
import app.shoku.domain.stockLevels
import app.shoku.domain.supplementStock
import app.shoku.domain.usualMeal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Self-test on the phone, driven over adb only (the receiver needs the DUMP permission, which only
 * the shell has). Works on TEST rows with fixed ids and removes them on "cleanup"; real data is only
 * read. Results go to logcat under the tag ShokuTest.
 *
 *   adb shell am broadcast -n app.shoku/.debug.SelfTestReceiver --es step <seed|remind|eat|buy|scan|prep|weekly|dump|cleanup> [--es code <ean>]
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ShokuApplication
        val step = intent.getStringExtra("step") ?: return
        val code = intent.getStringExtra("code")
        val pending = goAsync()
        app.appScope.launch {
            try {
                SelfTest(app).run(step, code)
            } catch (e: Throwable) {
                Log.e(TAG, "step $step failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

private const val TAG = "ShokuTest"
private const val SUPP = "test-supp"
private const val FOOD = "test-food"
private const val BATCH = "test-batch"

class SelfTest(private val app: ShokuApplication) {
    private val repo = app.repository
    private val home = app.home
    private val dao = app.database.home()
    private val prefs = app.getSharedPreferences("selftest", Context.MODE_PRIVATE)
    private val today get() = LocalDate.now()

    private fun log(msg: String) = Log.i(TAG, msg)

    suspend fun run(step: String, code: String?) = when (step) {
        "seed" -> seed()
        "remind" -> work(Meals.LUNCH, SUPP)
        "meal" -> work(code ?: Meals.LUNCH, null)
        "usual" -> Meals.ALL.forEach { m ->
            val day = LocalDate.parse(code ?: today.toString())
            val u = usualMeal(repo.allEntries.first(), m, day, repo.library.first())
            log("usual $m as of $day: ${u.joinToString { "${it.name} ${it.qty} ${it.unit}" }} = ${Math.round(u.sumOf { it.nutrients?.kcal ?: 0.0 })} kcal")
        }
        "prep" -> work(Reminders.PREP, null)
        "weekly" -> work(Reminders.WEEKLY, null)
        "shopping" -> work(Reminders.SHOPPING, null)
        "eat" -> eat()
        "buy" -> buy()
        "scan" -> scan(code ?: "3017620422003")
        "dump" -> dump()
        "cleanup" -> cleanup()
        "forget" -> forget(code ?: "")
        "finishbatches" -> finishBatches()
        "snapshot" -> snapshot()
        "dietnotes" -> { DietNotes.text = code.orEmpty().split('|').joinToString("\n") { it.trim() }; log("diet notes: ${DietNotes.lines.size} rules") }
        "remove" -> remove(code.orEmpty().split(',').filter { it.contains(':') })
        "clearnotifications" -> { androidx.core.app.NotificationManagerCompat.from(app).cancelAll(); log("notifications cleared") }
        "revert" -> revert()
        "health" -> log("health export: ${app.health.run()} records written or removed")
        "restore" -> restore(code.orEmpty().split(',').filter { it.isNotBlank() })
        else -> log("unknown step $step")
    }

    private suspend fun seed() {
        home.saveSupplement(
            Supplement(SUPP, "TEST supplement", "self-test", "tablet", 1.0, Meals.LUNCH,
                encodeSchedule(listOf(ScheduleRule(ScheduleRule.DAY))), startDate = today.toString(), position = 99),
        )
        val s = home.supplements.first { st -> st.supplements.any { it.id == SUPP } }.supplements.first { it.id == SUPP }
        home.countSupplement(s, 10.0)
        val food = Food(FOOD, "TEST rice", "g", 100.0, 350.0, 7.0, 1.0, source = "selftest")
        repo.saveFood(food, updatePast = false)
        repo.library.first { it.foods.containsKey(FOOD) }
        home.addFoodStock(food, 1000.0)
        log("seed ok: TEST supplement (10 tablets), TEST rice 1000 g")
        dump()
    }

    /** Runs the reminder worker now (meal reminder limited to [only], or prep/weekly). */
    private fun work(key: String, only: String?) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf(Reminders.EXTRA_MEAL to key, Reminders.EXTRA_ONLY to only))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("selftest-$key", ExistingWorkPolicy.REPLACE, req)
        log("worker $key queued")
    }

    /** Eats 3 x 200 g of TEST rice today and cooks a batch with 100 g: stock should drop by 700 g. */
    private suspend fun eat() {
        val now = System.currentTimeMillis()
        (1..3).forEach { i ->
            repo.add(LogEntry("test-e$i", today.toString(), Meals.LUNCH, RefType.FOOD, FOOD, "TEST rice", "g", 200.0,
                700.0, 14.0, 2.0, status = EntryStatus.EATEN, source = "selftest", createdAt = now + i, updatedAt = now + i))
        }
        repo.saveBatch(
            Batch(BATCH, null, "TEST batch", today.toString(), 2.0, totalKcal = null, totalProtein = null, totalFibre = null),
            listOf(IngredientRef(RefType.FOOD, FOOD, "g", 100.0)),
        )
        log("eat ok: 600 g eaten, 100 g cooked")
        dump()
    }

    private suspend fun buy() {
        val food = repo.library.first().foods.getValue(FOOD)
        home.addFoodStock(food, 500.0)
        log("buy ok: +500 g")
        dump()
    }

    /** The scanner's path after it reads [code]: library by barcode, else Open Food Facts, then into stock. */
    private suspend fun scan(code: String) {
        val existing = repo.foodByBarcode(code)
        var scannedPack: Double? = null
        val food = existing ?: OpenFoodFacts.lookup(code)?.let { p ->
            scannedPack = p.packQty
            val (f, portions) = p.toLibraryFood()
            repo.saveFood(f.copy(source = "selftest-scan"), updatePast = false)
            if (portions.isNotEmpty()) repo.savePortions(portions)
            prefs.edit().putString("scanned", f.id).apply()
            f
        }
        if (food == null) { log("scan $code: not on Open Food Facts"); return }
        val lib = repo.library.first { it.foods.containsKey(food.id) }
        val pack = scannedPack ?: lib.portionsOf(food).firstOrNull { it.label == "pack" }?.qty
        log("scan $code: ${food.name} [${food.brand}] ${food.kcal} kcal/100 ${food.unit}, pack=${pack ?: "?"}, was in library: ${existing != null}")
        if (existing == null) {
            home.addFoodStock(food, pack ?: 100.0)
            dump()
        }
    }

    private suspend fun dump() {
        val lib = repo.library.first()
        val (items, moves) = home.stock.first()
        val st = home.supplements.first()
        val plan = home.plan.first()
        val levels = lib.stockLevels(items, moves, repo.allEntries.first(), st.doses, plan, today)
        val scanned = prefs.getString("scanned", null)
        levels.filter { it.item.foodId == FOOD || it.item.supplementId == SUPP || (scanned != null && it.item.foodId == scanned) }.forEach { l ->
            log("stock ${l.item.name}: qty=${l.qty} ${l.item.unit} perDay=${l.perDay} daysLeft=${l.daysLeft}")
        }
        st.supplements.firstOrNull { it.id == SUPP }?.let { s ->
            log("supplement TEST: takenToday=${st.taken(s, today)} stock=${supplementStock(st.stockItem(s), st.doses, st.moves)}")
        }
        lib.shoppingList(levels, plan).filter { it.name.startsWith("TEST") }.forEach { log("to buy: ${it.name} (${it.reason})") }
    }

    /** Before a feature test on real data: remembers the time, the templates and which template each slot uses. */
    private suspend fun snapshot() {
        val plan = home.plan.first()
        prefs.edit()
            .putLong("snapAt", System.currentTimeMillis())
            .putString("snapTemplates", plan.templates.filter { !it.deleted }.joinToString(",") { it.id })
            .putString("snapSlots", plan.slots.filter { !it.deleted }.joinToString(",") { "${it.weekday}|${it.meal}|${it.templateId ?: ""}" })
            .apply()
        log("snapshot: ${plan.templates.count { !it.deleted }} templates, ${plan.slots.count { it.templateId != null }} slots in use")
    }

    /**
     * Undoes a feature test since [snapshot]: removes "usual" log entries and weigh-ins made since, deletes
     * templates that weren't there, puts every slot back, then exports so Health Connect drops them too.
     */
    private suspend fun revert() {
        val at = prefs.getLong("snapAt", 0).takeIf { it > 0 } ?: run { log("revert: no snapshot"); return }
        val usual = repo.allEntries.first().filter { it.source == "usual" && it.createdAt >= at }
        repo.deleteAll(usual)
        val weights = app.database.weights().all().filter { !it.deleted && it.updatedAt >= at }
        weights.forEach { repo.deleteWeight(it) }
        val keep = prefs.getString("snapTemplates", "").orEmpty().split(',').toSet()
        val plan = home.plan.first()
        val added = plan.templates.filter { !it.deleted && it.id !in keep }
        added.forEach { home.deleteTemplate(it) }
        val saved = prefs.getString("snapSlots", "").orEmpty().split(',').filter { it.isNotBlank() }.map { it.split('|') }
        saved.forEach { (d, meal, t) -> home.setDay(d.toInt(), meal, t.ifBlank { null }) }
        val savedKeys = saved.map { "${it[0]}|${it[1]}" }.toSet()
        home.plan.first().slots.filter { !it.deleted && it.templateId != null && "${it.weekday}|${it.meal}" !in savedKeys }
            .forEach { home.setDay(it.weekday, it.meal, null) }
        val exported = app.health.run()
        log("revert: ${usual.size} usual entries, ${weights.size} weights, ${added.size} templates removed; ${saved.size} slots restored; health export $exported")
    }

    /** Soft-deletes test rows given as "food:<id>", "dish:<id>", "batch:<id>" or "entry:<id>" (synced as deletions). */
    private suspend fun remove(refs: List<String>) {
        val lib = repo.library.first()
        refs.map { it.split(':', limit = 2) }.forEach { (type, id) ->
            when (type) {
                "batch" -> lib.batches[id]?.let { repo.deleteBatch(it) }
                "entry" -> repo.allEntries.first().firstOrNull { it.id == id }?.let { repo.delete(it) }
                "dish" -> lib.dishes[id]?.let { repo.saveDish(it.copy(deleted = true), emptyList(), updatePast = false) }
                "food" -> lib.foods[id]?.let { f ->
                    repo.saveFood(f.copy(deleted = true), updatePast = false)
                    repo.savePortions(lib.portions[f.id].orEmpty().map { it.copy(deleted = true) })
                }
            }
        }
        log("remove: $refs")
    }

    /** Marks every unfinished batch as finished (eaten); nothing is deleted. */
    private suspend fun finishBatches() {
        val open = repo.library.first().batches.values.filter { !it.finished && !it.deleted }
        open.forEach { repo.setBatchFinished(it, true) }
        log("finishbatches: ${open.size} marked finished: ${open.map { "${it.date} ${it.dishName}" }}")
    }

    /** Brings back log entries (by id) that were deleted by mistake. */
    private suspend fun restore(ids: List<String>) {
        val all = app.database.logs().allIncludingDeleted().filter { it.id in ids && it.deleted }
        all.forEach { repo.restore(it) }
        log("restore: ${all.size} entries back: ${all.map { "${it.date} ${it.meal} ${it.name}" }}")
    }

    /** Removes a product saved during a scan test (by barcode), with its units and stock. */
    private suspend fun forget(code: String) {
        val now = System.currentTimeMillis()
        val foods = repo.library.first().foods.values.filter { it.barcode == code && code.isNotBlank() }
        foods.forEach { f ->
            repo.saveFood(f.copy(deleted = true), updatePast = false)
            repo.savePortions(repo.library.first().portions[f.id].orEmpty().map { it.copy(deleted = true) })
            dao.upsertStock(dao.allStock().filter { it.foodId == f.id }.map { it.copy(deleted = true, updatedAt = now) })
        }
        log("forget $code: removed ${foods.map { it.name }}")
    }

    /** Removes every TEST row (soft delete, so the sync removes them on the server too). */
    private suspend fun cleanup() {
        val now = System.currentTimeMillis()
        val scanned = prefs.getString("scanned", null)
        val foodIds = setOfNotNull(FOOD, scanned)
        dao.upsertSupplements(dao.allSupplements().filter { it.id == SUPP }.map { it.copy(deleted = true, updatedAt = now) })
        dao.upsertDoses(dao.allDoses().filter { it.supplementId == SUPP }.map { it.copy(deleted = true, updatedAt = now) })
        val stock = dao.allStock().filter { it.supplementId == SUPP || it.foodId in foodIds }
        dao.upsertStock(stock.map { it.copy(deleted = true, updatedAt = now) })
        dao.upsertMoves(dao.allMoves().filter { m -> stock.any { it.id == m.itemId } }.map { it.copy(deleted = true, updatedAt = now) })
        repo.deleteAll(repo.allEntries.first().filter { it.refId in foodIds || it.id.startsWith("test-e") })
        repo.library.first().batches[BATCH]?.let { repo.deleteBatch(it) }
        foodIds.forEach { id -> repo.library.first().foods[id]?.let { repo.saveFood(it.copy(deleted = true), updatePast = false) } }
        prefs.edit().clear().apply()
        // The notifications the test posted (lunch reminder, prep, weekly summary).
        androidx.core.app.NotificationManagerCompat.from(app).apply { cancel(Reminders.notificationId(Meals.LUNCH)); cancel(2000); cancel(4000) }
        log("cleanup ok: ${stock.size} stock items, foods $foodIds removed")
    }
}
