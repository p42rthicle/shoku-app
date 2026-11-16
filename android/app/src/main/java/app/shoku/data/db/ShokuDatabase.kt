package app.shoku.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM foods WHERE deleted = 0")
    fun observeFoods(): Flow<List<Food>>

    @Query("SELECT * FROM dishes WHERE deleted = 0")
    fun observeDishes(): Flow<List<Dish>>

    @Query("SELECT * FROM dish_ingredients WHERE deleted = 0 ORDER BY position")
    fun observeDishIngredients(): Flow<List<DishIngredient>>

    @Query("SELECT * FROM batches WHERE deleted = 0")
    fun observeBatches(): Flow<List<Batch>>

    @Query("SELECT * FROM batch_ingredients WHERE deleted = 0 ORDER BY position")
    fun observeBatchIngredients(): Flow<List<BatchIngredient>>

    @Query("SELECT * FROM food_portions WHERE deleted = 0")
    fun observePortions(): Flow<List<FoodPortion>>

    @Upsert suspend fun upsertPortions(rows: List<FoodPortion>)
    @Query("SELECT * FROM food_portions") suspend fun allPortions(): List<FoodPortion>

    @Upsert suspend fun upsertFoods(rows: List<Food>)
    @Upsert suspend fun upsertDishes(rows: List<Dish>)
    @Upsert suspend fun upsertDishIngredients(rows: List<DishIngredient>)
    @Upsert suspend fun upsertBatches(rows: List<Batch>)
    @Upsert suspend fun upsertBatchIngredients(rows: List<BatchIngredient>)

    @Query("SELECT * FROM foods") suspend fun allFoods(): List<Food>
    @Query("SELECT * FROM dishes") suspend fun allDishes(): List<Dish>
    @Query("SELECT * FROM dish_ingredients") suspend fun allDishIngredients(): List<DishIngredient>
    @Query("SELECT * FROM batches") suspend fun allBatches(): List<Batch>
    @Query("SELECT * FROM batch_ingredients") suspend fun allBatchIngredients(): List<BatchIngredient>
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log_entries WHERE deleted = 0 AND date = :date ORDER BY createdAt")
    fun observeDay(date: String): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE deleted = 0")
    fun observeAll(): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE deleted = 0 AND date = :date AND meal = :meal ORDER BY createdAt")
    suspend fun meal(date: String, meal: String): List<LogEntry>

    @Query("SELECT * FROM log_entries")
    suspend fun allIncludingDeleted(): List<LogEntry>

    @Upsert suspend fun upsert(entry: LogEntry)
    @Upsert suspend fun upsertAll(entries: List<LogEntry>)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE deleted = 0 ORDER BY effectiveFrom")
    fun observeAll(): Flow<List<Goal>>

    @Query("SELECT * FROM goals")
    suspend fun all(): List<Goal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(goal: Goal)

    @Upsert suspend fun upsertAll(goals: List<Goal>)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY date")
    fun observeAll(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weights")
    suspend fun all(): List<WeightEntry>

    @Upsert suspend fun upsertAll(rows: List<WeightEntry>)
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM foods") suspend fun clearFoods()
    @Query("DELETE FROM dishes") suspend fun clearDishes()
    @Query("DELETE FROM dish_ingredients") suspend fun clearDishIngredients()
    @Query("DELETE FROM batches") suspend fun clearBatches()
    @Query("DELETE FROM batch_ingredients") suspend fun clearBatchIngredients()
    @Query("DELETE FROM log_entries") suspend fun clearLogs()
    @Query("DELETE FROM goals") suspend fun clearGoals()
}

@Database(
    entities = [Food::class, FoodPortion::class, Dish::class, DishIngredient::class, Batch::class, BatchIngredient::class, LogEntry::class, Goal::class, WeightEntry::class,
        Supplement::class, SupplementDose::class, MealTemplate::class, TemplateItem::class, PlanSlot::class, StockItem::class, StockMove::class],
    version = 6,
    exportSchema = true,
)
abstract class ShokuDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun logs(): LogDao
    abstract fun goals(): GoalDao
    abstract fun weights(): WeightDao
    abstract fun home(): HomeDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        fun create(context: Context): ShokuDatabase =
            Room.databaseBuilder(context, ShokuDatabase::class.java, "shoku.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}

/** Call inside `db.withTransaction { }`; used before importing a backup. */
suspend fun MaintenanceDao.clearAll() {
    clearFoods(); clearDishes(); clearDishIngredients(); clearBatches(); clearBatchIngredients(); clearLogs(); clearGoals()
}

/**
 * Version 2: generic foods and portions. Only adds columns and one table; no existing row,
 * id or number changes. The SQL matches schemas/.../2.json, which Room checks on open.
 */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_1_2_SQL = listOf(
    "ALTER TABLE `foods` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'product'",
    "ALTER TABLE `foods` ADD COLUMN `parentId` TEXT",
    "ALTER TABLE `foods` ADD COLUMN `aliases` TEXT NOT NULL DEFAULT ''",
    "CREATE TABLE IF NOT EXISTS `food_portions` (`id` TEXT NOT NULL, `foodId` TEXT NOT NULL, `label` TEXT NOT NULL, `qty` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_food_portions_foodId` ON `food_portions` (`foodId`)",
    "ALTER TABLE `log_entries` ADD COLUMN `basis` TEXT NOT NULL DEFAULT 'exact'",
    "ALTER TABLE `log_entries` ADD COLUMN `variant` TEXT",
)

/** Version 3: weigh-ins. Only adds a table. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `weights` (`date` TEXT NOT NULL, `kg` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`date`))")
    }
}

/** Version 4: supplements, meal plan, home stock. Only adds tables; SQL copied from schemas/.../4.json. */
/** Version 6: meal photos on log entries (only adds a nullable column; nothing is rewritten). */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `log_entries` ADD COLUMN `photos` TEXT")
    }
}

/** Version 5: a note on each log entry (only adds a nullable column; nothing is rewritten). */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `log_entries` ADD COLUMN `note` TEXT")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_3_4_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `supplements` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `detail` TEXT NOT NULL, `unit` TEXT NOT NULL, `dose` REAL NOT NULL, `slot` TEXT NOT NULL, `schedule` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT, `howTo` TEXT NOT NULL, `cautions` TEXT NOT NULL, `active` INTEGER NOT NULL, `position` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `supplement_doses` (`id` TEXT NOT NULL, `supplementId` TEXT NOT NULL, `date` TEXT NOT NULL, `qty` REAL NOT NULL, `taken` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_supplement_doses_supplementId` ON `supplement_doses` (`supplementId`)",
    "CREATE INDEX IF NOT EXISTS `index_supplement_doses_date` ON `supplement_doses` (`date`)",
    "CREATE TABLE IF NOT EXISTS `meal_templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `meal` TEXT NOT NULL, `position` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `template_items` (`id` TEXT NOT NULL, `templateId` TEXT NOT NULL, `refType` TEXT NOT NULL, `refId` TEXT, `label` TEXT NOT NULL, `unit` TEXT NOT NULL, `qty` REAL NOT NULL, `position` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_template_items_templateId` ON `template_items` (`templateId`)",
    "CREATE TABLE IF NOT EXISTS `plan_slots` (`id` TEXT NOT NULL, `weekday` INTEGER NOT NULL, `meal` TEXT NOT NULL, `templateId` TEXT, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `stock_items` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `foodId` TEXT, `supplementId` TEXT, `unit` TEXT NOT NULL, `place` TEXT NOT NULL, `tracked` INTEGER NOT NULL, `countQty` REAL, `countedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `lowAt` REAL, `bestBefore` TEXT, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_stock_items_foodId` ON `stock_items` (`foodId`)",
    "CREATE INDEX IF NOT EXISTS `index_stock_items_supplementId` ON `stock_items` (`supplementId`)",
    "CREATE TABLE IF NOT EXISTS `stock_moves` (`id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `delta` REAL NOT NULL, `at` INTEGER NOT NULL, `note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_stock_moves_itemId` ON `stock_moves` (`itemId`)",
)
