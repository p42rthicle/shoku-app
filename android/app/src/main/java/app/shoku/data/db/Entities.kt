package app.shoku.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Every row has a stable string id, an updatedAt timestamp and a soft-delete flag,
 * so the Catalyst sync in phase 3 can push and pull changes by id.
 * Nutrient values are nullable: null means "unknown", which is different from 0.
 */

object RefType {
    const val FOOD = "food"
    const val DISH = "dish"
    const val BATCH = "batch"
    const val QUICK = "quick" // free-text entry with typed-in macros, no library item
}

object FoodKind {
    /** A specific product, usually branded ("Milbona Vollmilch"). */
    const val PRODUCT = "product"
    /** Typical values for any brand ("Whole milk"); products link to it through parentId. */
    const val GENERIC = "generic"
    /** Something made at home that isn't a dish, e.g. a paneer recipe. */
    const val HOMEMADE = "homemade"
}

/** How sure an entry's numbers are. */
object Basis {
    /** Logged against a specific product or recipe. */
    const val EXACT = "exact"
    /** Logged against a generic food: typical values for any brand. */
    const val TYPICAL = "typical"
    /** Amount or item guessed, e.g. by AI from a description or photo. */
    const val ESTIMATE = "estimate"
}

object EntryStatus {
    const val PLANNED = "planned"
    const val EATEN = "eaten"
}

object Meals {
    const val BREAKFAST = "Breakfast"
    const val LUNCH = "Lunch"
    const val SNACKS = "Snacks"
    const val DINNER = "Dinner"
    val ALL = listOf(BREAKFAST, LUNCH, SNACKS, DINNER)
}

/** Nutrition is stored per [baseQty] of [unit], e.g. per 100 g or per 1 pc. */
@Serializable
@Entity(
    tableName = "foods",
    indices = [Index(value = ["name", "unit"]), Index("barcode")],
)
data class Food(
    @PrimaryKey val id: String,
    val name: String,
    val unit: String,
    val baseQty: Double,
    val kcal: Double?,
    val protein: Double?,
    val fibre: Double?,
    val carbs: Double? = null,
    val fat: Double? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val source: String = "manual",
    val archived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = FoodKind.PRODUCT) val kind: String = FoodKind.PRODUCT,
    /** The generic this product belongs to, e.g. Milbona Vollmilch -> Whole milk. */
    val parentId: String? = null,
    /** Other names for search and AI matching, comma separated ("Vollmilch, Milch"). */
    @ColumnInfo(defaultValue = "") val aliases: String = "",
)

/** A named amount of a food in the food's own unit, e.g. Banana "medium" = 110 g. Labels are never renamed. */
@Serializable
@Entity(tableName = "food_portions", indices = [Index("foodId")])
data class FoodPortion(
    @PrimaryKey val id: String,
    val foodId: String,
    val label: String,
    val qty: Double,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/**
 * A recipe template. Either computed from [DishIngredient]s, or `fixed` with
 * per-serving macros typed in (the "[o]" restaurant dishes).
 */
@Serializable
@Entity(tableName = "dishes", indices = [Index("name")])
data class Dish(
    @PrimaryKey val id: String,
    val name: String,
    val fixed: Boolean,
    val servings: Double,
    val yieldQty: Double? = null,
    val yieldUnit: String? = null,
    val fixedKcal: Double? = null,
    val fixedProtein: Double? = null,
    val fixedFibre: Double? = null,
    val fixedCarbs: Double? = null,
    val fixedFat: Double? = null,
    val archived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** [qty] is the amount for the whole recipe, not per serving. */
@Serializable
@Entity(tableName = "dish_ingredients", indices = [Index("dishId")])
data class DishIngredient(
    @PrimaryKey val id: String,
    val dishId: String,
    val refType: String,
    val refId: String,
    val unit: String,
    val qty: Double,
    val position: Int,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** One real cook of a dish. Totals are a snapshot so later template edits never change it. */
@Serializable
@Entity(tableName = "batches", indices = [Index("dishId"), Index("date")])
data class Batch(
    @PrimaryKey val id: String,
    val dishId: String?,
    val dishName: String,
    val date: String,
    val servings: Double,
    val finalQty: Double? = null,
    val finalUnit: String? = null,
    val yieldSource: String = "none",
    val totalKcal: Double?,
    val totalProtein: Double?,
    val totalFibre: Double?,
    val totalCarbs: Double? = null,
    val totalFat: Double? = null,
    val notes: String = "",
    val finished: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Serializable
@Entity(tableName = "batch_ingredients", indices = [Index("batchId")])
data class BatchIngredient(
    @PrimaryKey val id: String,
    val batchId: String,
    val refType: String,
    val refId: String,
    val unit: String,
    val qty: Double,
    val position: Int,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** Macros are snapshotted at log time; [name] keeps the label even if the item is renamed. */
@Serializable
@Entity(tableName = "log_entries", indices = [Index("date"), Index("refId")])
data class LogEntry(
    @PrimaryKey val id: String,
    val date: String,
    val meal: String,
    val refType: String,
    val refId: String?,
    val name: String,
    val unit: String,
    val qty: Double,
    val kcal: Double?,
    val protein: Double?,
    val fibre: Double?,
    val carbs: Double? = null,
    val fat: Double? = null,
    val status: String = EntryStatus.EATEN,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = Basis.EXACT) val basis: String = Basis.EXACT,
    /** Free-text brand or note when logged against a generic ("Weihenstephan"). */
    val variant: String? = null,
    /** The user's own note: where or how ("Bikanervala, Karol Bagh"). Version 5. */
    val note: String? = null,
)

/** Goals apply from [effectiveFrom] until the next goal row. */
@Serializable
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey val effectiveFrom: String,
    val kcal: Double,
    val protein: Double,
    val fibre: Double? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/** One weigh-in per day, in kg. */
@Serializable
@Entity(tableName = "weights")
data class WeightEntry(
    @PrimaryKey val date: String,
    val kg: Double,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)
