package app.shoku.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import app.shoku.data.db.ShokuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Optional one-way export to Health Connect: eaten entries as nutrition records and weigh-ins as
 * weight records. Each record carries Shoku's id, so re-exporting updates it instead of duplicating,
 * and entries that are deleted or set back to planned are removed again. Nothing is read back.
 */
class HealthExport(private val context: Context, private val db: ShokuDatabase) {
    private val prefs = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    val available: Boolean get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Exports what changed since the last run. Returns how many records were written or removed. */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        if (app.shoku.data.net.Network.blocked || !enabled || !hasPermissions()) return@withContext 0
        val since = prefs.getLong("lastExport", 0)
        val zone = ZoneId.systemDefault()
        val changed = db.logs().allIncludingDeleted().filter { it.updatedAt > since }
        val (keep, drop) = changed.partition { !it.deleted && it.status == EntryStatus.EATEN && it.kcal != null }
        val weights = db.weights().all().filter { it.updatedAt > since }
        val (wKeep, wDrop) = weights.partition { !it.deleted }

        if (drop.isNotEmpty()) client.deleteRecords(NutritionRecord::class, emptyList(), drop.map { "shoku-${it.id}" })
        if (wDrop.isNotEmpty()) client.deleteRecords(WeightRecord::class, emptyList(), wDrop.map { "shoku-w-${it.date}" })
        keep.chunked(500).forEach { part -> client.insertRecords(part.map { nutrition(it, zone) }) }
        if (wKeep.isNotEmpty()) {
            client.insertRecords(wKeep.map { w ->
                val t = LocalDate.parse(w.date).atTime(8, 0).atZone(zone)
                WeightRecord(
                    time = t.toInstant(), zoneOffset = t.offset, weight = Mass.kilograms(w.kg),
                    metadata = Metadata.manualEntry(clientRecordId = "shoku-w-${w.date}", clientRecordVersion = w.updatedAt),
                )
            })
        }
        val newest = (changed.map { it.updatedAt } + weights.map { it.updatedAt }).maxOrNull()
        if (newest != null) prefs.edit().putLong("lastExport", newest).apply()
        changed.size + weights.size
    }

    /** Entries have a day and a meal, not a time: each meal gets a typical time so they sort sensibly. */
    private fun nutrition(e: LogEntry, zone: ZoneId): NutritionRecord {
        val time = when (e.meal) {
            Meals.BREAKFAST -> LocalTime.of(8, 30)
            Meals.LUNCH -> LocalTime.of(13, 0)
            Meals.SNACKS -> LocalTime.of(16, 30)
            else -> LocalTime.of(20, 0)
        }
        val start = LocalDate.parse(e.date).atTime(time).atZone(zone)
        val end = start.plusMinutes(15)
        return NutritionRecord(
            startTime = start.toInstant(), startZoneOffset = start.offset,
            endTime = end.toInstant(), endZoneOffset = end.offset,
            energy = e.kcal?.let { Energy.kilocalories(it) },
            protein = e.protein?.let { Mass.grams(it) },
            dietaryFiber = e.fibre?.let { Mass.grams(it) },
            totalCarbohydrate = e.carbs?.let { Mass.grams(it) },
            totalFat = e.fat?.let { Mass.grams(it) },
            name = e.name,
            mealType = when (e.meal) {
                Meals.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
                Meals.LUNCH -> MealType.MEAL_TYPE_LUNCH
                Meals.SNACKS -> MealType.MEAL_TYPE_SNACK
                Meals.DINNER -> MealType.MEAL_TYPE_DINNER
                else -> MealType.MEAL_TYPE_UNKNOWN
            },
            metadata = Metadata.manualEntry(clientRecordId = "shoku-${e.id}", clientRecordVersion = e.updatedAt),
        )
    }

    /** Next export sends everything again (after first turning it on). */
    fun resetCursor() = prefs.edit().remove("lastExport").apply()

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )
    }
}
