package app.shoku.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/*
 * Database version 4: supplements, meal plan and home stock. Same conventions as the other tables:
 * string ids, updatedAt for last-write-wins sync, soft deletes.
 *
 * Stock is a ledger, not a counter: an item stores the amount counted at [StockItem.countedAt], and the
 * current amount is worked out from that minus what was used since (eaten entries, cooked batches,
 * supplement doses) plus purchases and corrections ([StockMove]). Nothing has to be decremented in
 * place, so sync conflicts and double counting can't happen.
 */

/** Where a supplement is taken: one of the meals, or a separate bedtime slot. */
object Slots {
    const val BEDTIME = "Bedtime"
    val ALL = Meals.ALL + BEDTIME
}

/**
 * A supplement and when to take it. [schedule] is a JSON list of rules (see domain/SupplementSchedule):
 * e.g. weekly on Sundays until a date, then the first Sunday of each month.
 */
@Serializable
@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey val id: String,
    val name: String,
    val detail: String = "",
    /** What one unit is called: tablet, capsule, g. */
    val unit: String = "tablet",
    val dose: Double = 1.0,
    val slot: String = Meals.BREAKFAST,
    val schedule: String = "[]",
    val startDate: String,
    val endDate: String? = null,
    /** How to take it ("with a meal that has fat"). */
    val howTo: String = "",
    /** When to stop or ask a doctor; shown as information only. */
    val cautions: String = "",
    val active: Boolean = true,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** One scheduled dose on one day. Id is "supplementId|date", so a day can't have two. */
@Serializable
@Entity(tableName = "supplement_doses", indices = [Index("supplementId"), Index("date")])
data class SupplementDose(
    @PrimaryKey val id: String,
    val supplementId: String,
    val date: String,
    val qty: Double,
    val taken: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** A reusable meal ("Egg bhurji breakfast"). */
@Serializable
@Entity(tableName = "meal_templates")
data class MealTemplate(
    @PrimaryKey val id: String,
    val name: String,
    val meal: String,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/**
 * One item of a template. refType "slot" is a placeholder filled when the plan is added
 * ("today's sabzi or dal": the batch in the fridge, chosen then).
 */
@Serializable
@Entity(tableName = "template_items", indices = [Index("templateId")])
data class TemplateItem(
    @PrimaryKey val id: String,
    val templateId: String,
    val refType: String,
    val refId: String?,
    val label: String = "",
    val unit: String,
    val qty: Double,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** Which template a weekday's meal uses. Id is "weekday|meal" (weekday 1 = Monday). */
@Serializable
@Entity(tableName = "plan_slots")
data class PlanSlot(
    @PrimaryKey val id: String,
    val weekday: Int,
    val meal: String,
    val templateId: String?,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

object StockPlaces {
    const val FRIDGE = "Fridge"
    const val FREEZER = "Freezer"
    const val PANTRY = "Pantry"
    const val SPICES = "Spices"
    const val SUPPLEMENTS = "Supplements"
    val ALL = listOf(FRIDGE, FREEZER, PANTRY, SPICES, SUPPLEMENTS)
}

/**
 * Something at home. Linked to a library food ([foodId]) or a supplement ([supplementId]) so that
 * eating or taking it uses it up. [tracked] false means status only (spices: have / low / out).
 */
@Serializable
@Entity(tableName = "stock_items", indices = [Index("foodId"), Index("supplementId")])
data class StockItem(
    @PrimaryKey val id: String,
    val name: String,
    val foodId: String? = null,
    val supplementId: String? = null,
    /** Unit of [countQty]: the food's own unit, or the supplement's. */
    val unit: String,
    val place: String = StockPlaces.PANTRY,
    val tracked: Boolean = true,
    val countQty: Double? = null,
    val countedAt: Long = System.currentTimeMillis(),
    /** For untracked items: have, low, out. */
    val status: String = "have",
    /** Put it on the shopping list below this amount (or when status is low/out). */
    val lowAt: Double? = null,
    val bestBefore: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** A purchase (+) or correction (+/-) to a stock item after it was counted. */
@Serializable
@Entity(tableName = "stock_moves", indices = [Index("itemId")])
data class StockMove(
    @PrimaryKey val id: String,
    val itemId: String,
    val delta: Double,
    val at: Long = System.currentTimeMillis(),
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** All version-4 tables: observe the live rows, read everything (sync/backup), upsert. */
@Dao
interface HomeDao {
    @Query("SELECT * FROM supplements WHERE deleted = 0 ORDER BY position") fun observeSupplements(): Flow<List<Supplement>>
    @Query("SELECT * FROM supplement_doses WHERE deleted = 0") fun observeDoses(): Flow<List<SupplementDose>>
    @Query("SELECT * FROM meal_templates WHERE deleted = 0 ORDER BY position") fun observeTemplates(): Flow<List<MealTemplate>>
    @Query("SELECT * FROM template_items WHERE deleted = 0 ORDER BY position") fun observeTemplateItems(): Flow<List<TemplateItem>>
    @Query("SELECT * FROM plan_slots WHERE deleted = 0") fun observePlanSlots(): Flow<List<PlanSlot>>
    @Query("SELECT * FROM stock_items WHERE deleted = 0") fun observeStock(): Flow<List<StockItem>>
    @Query("SELECT * FROM stock_moves WHERE deleted = 0") fun observeMoves(): Flow<List<StockMove>>

    @Query("SELECT * FROM supplements") suspend fun allSupplements(): List<Supplement>
    @Query("SELECT * FROM supplement_doses") suspend fun allDoses(): List<SupplementDose>
    @Query("SELECT * FROM meal_templates") suspend fun allTemplates(): List<MealTemplate>
    @Query("SELECT * FROM template_items") suspend fun allTemplateItems(): List<TemplateItem>
    @Query("SELECT * FROM plan_slots") suspend fun allPlanSlots(): List<PlanSlot>
    @Query("SELECT * FROM stock_items") suspend fun allStock(): List<StockItem>
    @Query("SELECT * FROM stock_moves") suspend fun allMoves(): List<StockMove>

    @Upsert suspend fun upsertSupplements(rows: List<Supplement>)
    @Upsert suspend fun upsertDoses(rows: List<SupplementDose>)
    @Upsert suspend fun upsertTemplates(rows: List<MealTemplate>)
    @Upsert suspend fun upsertTemplateItems(rows: List<TemplateItem>)
    @Upsert suspend fun upsertPlanSlots(rows: List<PlanSlot>)
    @Upsert suspend fun upsertStock(rows: List<StockItem>)
    @Upsert suspend fun upsertMoves(rows: List<StockMove>)
}
