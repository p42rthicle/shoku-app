package app.shoku.data.sync

import android.content.Context
import androidx.room.withTransaction
import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.data.db.ShokuDatabase
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.PlanSlot
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockMove
import app.shoku.data.db.Supplement
import app.shoku.data.db.SupplementDose
import app.shoku.data.db.TemplateItem
import app.shoku.data.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One app row as it travels to and from the server. */
@Serializable
data class SyncRecord(
    val kind: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val payload: String,
    val serverTs: Long? = null,
)

@Serializable private data class PushBody(val records: List<SyncRecord>)
@Serializable private data class PushResult(val inserted: Int, val updated: Int, val skipped: Int)
@Serializable private data class PullResult(val records: List<SyncRecord>, val next: Long, val more: Boolean)

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val lastPushed: Int = 0,
    val lastPulled: Int = 0,
)

/**
 * Two-way sync with the Catalyst function.
 *
 * Push: every row whose updatedAt is newer than the last successful push is sent; the server keeps
 * whichever copy has the newer updatedAt. Pull: records changed on the server since the saved cursor
 * are applied when they are newer than the local row. Deletes travel as soft-delete flags.
 */
class SyncEngine(context: Context, private val db: ShokuDatabase) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _status = MutableStateFlow(
        SyncStatus(lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0), lastError = prefs.getString(KEY_LAST_ERROR, null)),
    )
    val status: StateFlow<SyncStatus> = _status

    val configured: Boolean get() = Backend.configured

    suspend fun sync(): Result<SyncStatus> = mutex.withLock {
        if (!configured) return Result.failure(IllegalStateException("Sync is not configured in this build"))
        _status.value = _status.value.copy(running = true)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val pushed = push()
                val pulled = pull()
                pushed to pulled
            }
        }
        val now = System.currentTimeMillis()
        _status.value = result.fold(
            onSuccess = { (pushed, pulled) ->
                prefs.edit().putLong(KEY_LAST_SYNC, now).remove(KEY_LAST_ERROR).apply()
                SyncStatus(false, now, null, pushed, pulled)
            },
            onFailure = { e ->
                val msg = e.message ?: e.javaClass.simpleName
                prefs.edit().putString(KEY_LAST_ERROR, msg).apply()
                _status.value.copy(running = false, lastError = msg)
            },
        )
        result.map { _status.value }
    }

    // ---------- Push ----------

    private suspend fun push(): Int {
        val since = prefs.getLong(KEY_LAST_PUSH, 0)
        val records = localRecords().filter { it.updatedAt > since }.sortedBy { it.updatedAt }
        if (records.isEmpty()) return 0
        for (chunk in records.chunked(200)) {
            post("/sync/push", json.encodeToString(PushBody.serializer(), PushBody(chunk)))
                .let { json.decodeFromString(PushResult.serializer(), it) }
        }
        // Only advance once everything is accepted; re-pushing is harmless because the server skips equal copies.
        prefs.edit().putLong(KEY_LAST_PUSH, records.maxOf { it.updatedAt }).apply()
        return records.size
    }

    private suspend fun localRecords(): List<SyncRecord> {
        val lib = db.library()
        return buildList {
            lib.allFoods().forEach { add(record(KIND_FOOD, it.id, it.updatedAt, it.deleted, Food.serializer(), it)) }
            lib.allPortions().forEach { add(record(KIND_PORTION, it.id, it.updatedAt, it.deleted, FoodPortion.serializer(), it)) }
            lib.allDishes().forEach { add(record(KIND_DISH, it.id, it.updatedAt, it.deleted, Dish.serializer(), it)) }
            lib.allDishIngredients().forEach { add(record(KIND_DISH_ING, it.id, it.updatedAt, it.deleted, DishIngredient.serializer(), it)) }
            lib.allBatches().forEach { add(record(KIND_BATCH, it.id, it.updatedAt, it.deleted, Batch.serializer(), it)) }
            lib.allBatchIngredients().forEach { add(record(KIND_BATCH_ING, it.id, it.updatedAt, it.deleted, BatchIngredient.serializer(), it)) }
            db.logs().allIncludingDeleted().forEach { add(record(KIND_LOG, it.id, it.updatedAt, it.deleted, LogEntry.serializer(), it)) }
            db.goals().all().forEach { add(record(KIND_GOAL, it.effectiveFrom, it.updatedAt, it.deleted, Goal.serializer(), it)) }
            db.weights().all().forEach { add(record(KIND_WEIGHT, it.date, it.updatedAt, it.deleted, app.shoku.data.db.WeightEntry.serializer(), it)) }
            val h = db.home()
            h.allSupplements().forEach { add(record("supplement", it.id, it.updatedAt, it.deleted, Supplement.serializer(), it)) }
            h.allDoses().forEach { add(record("supplement_dose", it.id, it.updatedAt, it.deleted, SupplementDose.serializer(), it)) }
            h.allTemplates().forEach { add(record("meal_template", it.id, it.updatedAt, it.deleted, MealTemplate.serializer(), it)) }
            h.allTemplateItems().forEach { add(record("template_item", it.id, it.updatedAt, it.deleted, TemplateItem.serializer(), it)) }
            h.allPlanSlots().forEach { add(record("plan_slot", it.id, it.updatedAt, it.deleted, PlanSlot.serializer(), it)) }
            h.allStock().forEach { add(record("stock_item", it.id, it.updatedAt, it.deleted, StockItem.serializer(), it)) }
            h.allMoves().forEach { add(record("stock_move", it.id, it.updatedAt, it.deleted, StockMove.serializer(), it)) }
        }
    }

    private fun <T> record(kind: String, id: String, updatedAt: Long, deleted: Boolean, s: KSerializer<T>, value: T) =
        SyncRecord(kind, id, updatedAt, deleted, asciiOnly(json.encodeToString(s, value)))

    // ---------- Pull ----------

    private suspend fun pull(): Int {
        var cursor = prefs.getLong(KEY_PULL_CURSOR, 0)
        var applied = 0
        while (true) {
            val page = json.decodeFromString(PullResult.serializer(), get("/sync/pull?since=$cursor"))
            if (page.records.isNotEmpty()) applied += apply(page.records)
            cursor = page.next
            prefs.edit().putLong(KEY_PULL_CURSOR, cursor).apply()
            if (!page.more) break
        }
        return applied
    }

    /** Applies server records that are newer than the local copy. Returns how many were written. */
    private suspend fun apply(records: List<SyncRecord>): Int = db.withTransaction {
        val lib = db.library()
        var n = 0
        val byKind = records.groupBy { it.kind }

        suspend fun <T> merge(kind: String, s: KSerializer<T>, local: Map<String, Long>, write: suspend (List<T>) -> Unit) {
            val rows = byKind[kind].orEmpty()
                .filter { r -> (local[r.id] ?: Long.MIN_VALUE) < r.updatedAt }
                .map { json.decodeFromString(s, it.payload) }
            if (rows.isNotEmpty()) { write(rows); n += rows.size }
        }

        merge(KIND_FOOD, Food.serializer(), lib.allFoods().associate { it.id to it.updatedAt }) { lib.upsertFoods(it) }
        merge(KIND_PORTION, FoodPortion.serializer(), lib.allPortions().associate { it.id to it.updatedAt }) { lib.upsertPortions(it) }
        merge(KIND_DISH, Dish.serializer(), lib.allDishes().associate { it.id to it.updatedAt }) { lib.upsertDishes(it) }
        merge(KIND_DISH_ING, DishIngredient.serializer(), lib.allDishIngredients().associate { it.id to it.updatedAt }) { lib.upsertDishIngredients(it) }
        merge(KIND_BATCH, Batch.serializer(), lib.allBatches().associate { it.id to it.updatedAt }) { lib.upsertBatches(it) }
        merge(KIND_BATCH_ING, BatchIngredient.serializer(), lib.allBatchIngredients().associate { it.id to it.updatedAt }) { lib.upsertBatchIngredients(it) }
        merge(KIND_LOG, LogEntry.serializer(), db.logs().allIncludingDeleted().associate { it.id to it.updatedAt }) { db.logs().upsertAll(it) }
        merge(KIND_GOAL, Goal.serializer(), db.goals().all().associate { it.effectiveFrom to it.updatedAt }) { db.goals().upsertAll(it) }
        merge(KIND_WEIGHT, app.shoku.data.db.WeightEntry.serializer(), db.weights().all().associate { it.date to it.updatedAt }) { db.weights().upsertAll(it) }
        val h = db.home()
        merge("supplement", Supplement.serializer(), h.allSupplements().associate { it.id to it.updatedAt }) { h.upsertSupplements(it) }
        merge("supplement_dose", SupplementDose.serializer(), h.allDoses().associate { it.id to it.updatedAt }) { h.upsertDoses(it) }
        merge("meal_template", MealTemplate.serializer(), h.allTemplates().associate { it.id to it.updatedAt }) { h.upsertTemplates(it) }
        merge("template_item", TemplateItem.serializer(), h.allTemplateItems().associate { it.id to it.updatedAt }) { h.upsertTemplateItems(it) }
        merge("plan_slot", PlanSlot.serializer(), h.allPlanSlots().associate { it.id to it.updatedAt }) { h.upsertPlanSlots(it) }
        merge("stock_item", StockItem.serializer(), h.allStock().associate { it.id to it.updatedAt }) { h.upsertStock(it) }
        merge("stock_move", StockMove.serializer(), h.allMoves().associate { it.id to it.updatedAt }) { h.upsertMoves(it) }
        n
    }

    // ---------- HTTP ----------

    private fun get(path: String) = Backend.get(path)
    private fun post(path: String, body: String) = Backend.post(path, body)

    companion object {
        const val KIND_FOOD = "food"
        const val KIND_PORTION = "food_portion"
        const val KIND_DISH = "dish"
        const val KIND_DISH_ING = "dish_ingredient"
        const val KIND_BATCH = "batch"
        const val KIND_BATCH_ING = "batch_ingredient"
        const val KIND_LOG = "log"
        const val KIND_GOAL = "goal"
        const val KIND_WEIGHT = "weight"

        private const val KEY_LAST_PUSH = "lastPushUpdatedAt"
        private const val KEY_PULL_CURSOR = "pullCursor"
        private const val KEY_LAST_SYNC = "lastSyncAt"
        private const val KEY_LAST_ERROR = "lastError"

        /**
         * Catalyst Data Store can't hold 4-byte UTF-8 (emoji), so every non-ASCII character is written as a
         * JSON \u escape. Non-ASCII can only appear inside JSON strings, so this keeps the JSON valid.
         */
        fun asciiOnly(s: String): String {
            if (s.all { it.code < 0x7F }) return s
            val sb = StringBuilder(s.length + 16)
            for (c in s) if (c.code < 0x7F) sb.append(c) else sb.append("\\u").append(String.format("%04x", c.code))
            return sb.toString()
        }
    }
}
