package app.shoku.data.backup

import app.shoku.data.db.Batch
import app.shoku.data.db.BatchIngredient
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything in a backup, as database rows. */
data class BackupData(
    val foods: List<Food>,
    val dishes: List<Dish>,
    val dishIngredients: List<DishIngredient>,
    val batches: List<Batch>,
    val batchIngredients: List<BatchIngredient>,
    val logs: List<LogEntry>,
    val goals: List<Goal>,
    val warnings: List<String> = emptyList(),
    val portions: List<FoodPortion> = emptyList(),
    val weights: List<app.shoku.data.db.WeightEntry> = emptyList(),
)

/**
 * Writes backups in the Streamlit app's CSV format, and parses that format (used by the tests
 * that check the Kotlin port against the old data).
 * Names become stable ids; the same backup always produces the same ids, so re-importing is safe.
 * Exports keep the Streamlit columns first, so the old app can still read them, and add new columns after.
 */
object LegacyBackup {

    fun writeZip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---------- Import ----------

    fun parse(files: Map<String, String>): BackupData {
        val warnings = mutableListOf<String>()
        val now = System.currentTimeMillis()

        val foods = Csv.parse(files.getValue("foods.csv")).mapNotNull { r ->
            val name = r.text("food_name") ?: return@mapNotNull null
            val unit = r.text("unit") ?: ""
            val baseQty = r.num("base_qty")?.takeIf { it > 0 } ?: 1.0
            // Same as normalize_food_row: fall back to per-unit values when the base value is empty.
            fun base(baseCol: String, perUnitCol: String) = r.num(baseCol) ?: r.num(perUnitCol)?.times(baseQty)
            Food(
                id = r.text("id") ?: foodId(name, unit), name = name, unit = unit, baseQty = baseQty,
                kcal = base("calories_base", "cal_per_unit"),
                protein = base("protein_base", "protein_per_unit"),
                fibre = base("fiber_base", "fiber_per_unit"),
                carbs = r.num("carbs_base"), fat = r.num("fat_base"),
                barcode = r.text("barcode"), brand = r.text("brand"), source = r.text("source") ?: "import",
                archived = r.text("archived")?.lowercase() == "true", updatedAt = now,
                kind = r.text("kind") ?: FoodKind.PRODUCT, parentId = r.text("parent_id"), aliases = r.text("aliases") ?: "",
            )
        }.distinctBy { it.id }
        val foodIds = foods.map { it.id }.toSet()
        val foodIdByNameUnit = foods.associate { (it.name to it.unit) to it.id }

        val dishes = Csv.parse(files.getValue("dishes.csv")).mapNotNull { r ->
            val name = r.text("dish_name") ?: return@mapNotNull null
            val cal = r.num("cal_override")
            val prot = r.num("protein_override")
            Dish(
                id = r.text("id") ?: dishId(name), name = name,
                fixed = cal != null && prot != null,
                servings = r.num("servings")?.takeIf { it > 0 } ?: 1.0,
                yieldQty = r.num("yield_qty")?.takeIf { it > 0 },
                yieldUnit = r.text("yield_unit"),
                fixedKcal = cal, fixedProtein = prot, fixedFibre = r.num("fiber_override"),
                fixedCarbs = r.num("carbs_override"), fixedFat = r.num("fat_override"),
                archived = r.text("archived")?.lowercase() == "true", updatedAt = now,
            )
        }.distinctBy { it.id }
        val dishIds = dishes.map { it.id }.toSet()
        val dishIdByName = dishes.associate { it.name to it.id }

        val batchRows = files["batches.csv"]?.let { Csv.parse(it) }.orEmpty()
        val batches = batchRows.mapNotNull { r ->
            val id = r.text("batch_id") ?: return@mapNotNull null
            val dishName = r.text("dish_name") ?: ""
            Batch(
                id = id, dishId = (r.text("dish_id") ?: dishIdByName[dishName])?.takeIf { it in dishIds }, dishName = dishName,
                date = r.text("batch_date") ?: id.split('_').getOrNull(1)?.let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" } ?: "",
                servings = r.num("servings")?.takeIf { it > 0 } ?: 1.0,
                finalQty = r.num("final_qty")?.takeIf { it > 0 },
                finalUnit = r.text("final_unit"),
                yieldSource = r.text("yield_source") ?: "none",
                totalKcal = r.num("total_calories"), totalProtein = r.num("total_protein"), totalFibre = r.num("total_fiber"),
                totalCarbs = r.num("total_carbs"), totalFat = r.num("total_fat"),
                notes = r.text("notes") ?: "",
                finished = r.text("finished")?.lowercase() == "true",
                updatedAt = now,
            )
        }.distinctBy { it.id }
        val batchIds = batches.map { it.id }.toSet()

        fun ref(type: String, name: String?, unit: String?, batchId: String?, context: String, explicitId: String? = null): Pair<String, String> {
            val u = unit ?: ""
            if (explicitId != null) return type to explicitId
            return when (type) {
                RefType.DISH -> {
                    val id = dishIdByName[name ?: ""] ?: dishId(name ?: "")
                    if (id !in dishIds) warnings += "$context: dish \"$name\" not found"
                    RefType.DISH to id
                }
                RefType.BATCH -> {
                    val id = batchId ?: ""
                    if (id !in batchIds) warnings += "$context: batch $id not found"
                    RefType.BATCH to id
                }
                else -> {
                    val id = foodIdByNameUnit[(name ?: "") to u] ?: foodId(name ?: "", u)
                    if (id !in foodIds) warnings += "$context: food \"$name [$u]\" not found"
                    RefType.FOOD to id
                }
            }
        }

        val dishIngredients = Csv.parse(files.getValue("dish_ingredients.csv")).mapIndexedNotNull { i, r ->
            val dishName = r.text("dish_name") ?: return@mapIndexedNotNull null
            val (type, refId) = ref(ingredientType(r), r.text("ingredient_food_name"), r.text("ingredient_unit"), r.text("ingredient_batch_id"), "Dish $dishName", r.text("ref_id"))
            DishIngredient(
                id = r.text("id") ?: stableId("dish_ing", dishName, i.toString()),
                dishId = r.text("dish_id") ?: dishIdByName[dishName] ?: dishId(dishName),
                refType = type, refId = refId, unit = r.text("ingredient_unit") ?: "",
                qty = r.num("ingredient_qty_per_serving") ?: 0.0, position = r.num("position")?.toInt() ?: i, updatedAt = now,
            )
        }

        val batchIngredients = files["batch_ingredients.csv"]?.let { Csv.parse(it) }.orEmpty().mapIndexedNotNull { i, r ->
            val batchId = r.text("batch_id") ?: return@mapIndexedNotNull null
            val (type, refId) = ref(ingredientType(r), r.text("ingredient_food_name"), r.text("ingredient_unit"), r.text("ingredient_batch_id"), "Batch $batchId", r.text("ref_id"))
            BatchIngredient(
                id = r.text("id") ?: stableId("batch_ing", batchId, i.toString()), batchId = batchId,
                refType = type, refId = refId, unit = r.text("ingredient_unit") ?: "",
                qty = r.num("ingredient_qty") ?: 0.0, position = r.num("position")?.toInt() ?: i, updatedAt = now,
            )
        }

        val logs = Csv.parse(files.getValue("logs.csv")).mapIndexedNotNull { i, r ->
            val date = r.text("date") ?: return@mapIndexedNotNull null
            val name = r.text("name") ?: ""
            val unit = r.text("unit") ?: ""
            val type = r.text("type")?.lowercase() ?: RefType.FOOD
            val refId = r.text("ref_id") ?: when (type) {
                RefType.DISH -> dishIdByName[name]?.takeIf { it in dishIds }
                RefType.BATCH -> r.text("batch_id")?.takeIf { it in batchIds }
                RefType.FOOD -> foodIdByNameUnit[name to unit]?.takeIf { it in foodIds }
                else -> null
            }
            val id = r.text("log_id") ?: stableId("log", date, i.toString())
            // Every imported entry counts as eaten: the old "checked" box was never used consistently.
            val status = r.text("status") ?: EntryStatus.EATEN
            LogEntry(
                id = id, date = date, meal = r.text("meal") ?: "Snacks", refType = type, refId = refId,
                name = name, unit = unit, qty = r.num("qty") ?: 0.0,
                kcal = r.num("calories"), protein = r.num("protein"), fibre = r.num("fiber"),
                carbs = r.num("carbs"), fat = r.num("fat"),
                status = status, source = r.text("source") ?: "import",
                createdAt = r.num("created_at")?.toLong() ?: createdAtFromLogId(id) ?: now, updatedAt = now,
                basis = r.text("basis") ?: "exact", variant = r.text("variant"), note = r.text("note"),
            )
        }.distinctBy { it.id }

        val goals = Csv.parse(files.getValue("goals.csv")).mapNotNull { r ->
            val date = r.text("date") ?: return@mapNotNull null
            val kcal = r.num("calorie_goal") ?: return@mapNotNull null
            Goal(effectiveFrom = date, kcal = kcal, protein = r.num("protein_goal") ?: 0.0, fibre = r.num("fiber_goal"), updatedAt = now)
        }.distinctBy { it.effectiveFrom }

        val portions = files["food_portions.csv"]?.let { Csv.parse(it) }.orEmpty().mapNotNull { r ->
            FoodPortion(r.text("id") ?: return@mapNotNull null, r.text("food_id") ?: return@mapNotNull null, r.text("label") ?: return@mapNotNull null, r.num("qty") ?: return@mapNotNull null, updatedAt = now)
        }
        return BackupData(foods, dishes, dishIngredients, batches, batchIngredients, logs, goals, warnings.distinct(), portions)
    }

    private fun ingredientType(r: Map<String, String>): String =
        when (r.text("ingredient_type")?.lowercase()) {
            RefType.DISH -> RefType.DISH
            RefType.BATCH -> RefType.BATCH
            else -> RefType.FOOD
        }

    /** log_20260501_161111854278 -> epoch millis, so imported entries keep their original order. */
    private fun createdAtFromLogId(id: String): Long? {
        val m = Regex("""log_(\d{8})_(\d{6})(\d{0,6})""").find(id) ?: return null
        val (d, t, frac) = m.destructured
        return runCatching {
            val ldt = java.time.LocalDateTime.of(
                d.take(4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
                t.take(2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt(),
            )
            ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + (frac.padEnd(6, '0').take(3).toLongOrNull() ?: 0)
        }.getOrNull()
    }

    fun foodId(name: String, unit: String) = stableId("food", name.trim(), unit.trim())
    fun dishId(name: String) = stableId("dish", name.trim())
    private fun stableId(vararg parts: String) = UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

    // ---------- Export ----------

    fun toCsvFiles(d: BackupData): Map<String, String> {
        val foodsById = d.foods.associateBy { it.id }
        val dishesById = d.dishes.associateBy { it.id }
        fun refName(type: String, id: String) = when (type) {
            RefType.FOOD -> foodsById[id]?.name ?: ""
            RefType.DISH -> dishesById[id]?.name ?: ""
            else -> d.batches.firstOrNull { it.id == id }?.dishName ?: ""
        }
        fun ingType(type: String) = if (type == RefType.FOOD) "" else type

        val foods = Csv.write(
            listOf("food_name", "unit", "base_qty", "calories_base", "protein_base", "fiber_base", "cal_per_unit", "protein_per_unit", "fiber_per_unit", "carbs_base", "fat_base", "barcode", "id", "brand", "source", "archived", "kind", "parent_id", "aliases"),
            d.foods.filter { !it.deleted }.map { f ->
                val b = if (f.baseQty > 0) f.baseQty else 1.0
                listOf(f.name, f.unit, n(f.baseQty), n(f.kcal), n(f.protein), n(f.fibre), n(f.kcal?.div(b)), n(f.protein?.div(b)), n(f.fibre?.div(b)), n(f.carbs), n(f.fat), f.barcode ?: "", f.id, f.brand ?: "", f.source, f.archived.toString(), f.kind, f.parentId ?: "", f.aliases)
            },
        )
        val dishes = Csv.write(
            listOf("dish_name", "cal_override", "protein_override", "fiber_override", "servings", "yield_qty", "yield_unit", "carbs_override", "fat_override", "id", "archived"),
            d.dishes.filter { !it.deleted }.map { x ->
                listOf(x.name, if (x.fixed) n(x.fixedKcal) else "", if (x.fixed) n(x.fixedProtein) else "", if (x.fixed) n(x.fixedFibre) else "", n(x.servings), n(x.yieldQty), x.yieldUnit ?: "", if (x.fixed) n(x.fixedCarbs) else "", if (x.fixed) n(x.fixedFat) else "", x.id, x.archived.toString())
            },
        )
        val dishIngs = Csv.write(
            listOf("dish_name", "ingredient_type", "ingredient_food_name", "ingredient_unit", "ingredient_qty_per_serving", "ingredient_batch_id", "id", "dish_id", "ref_id", "position"),
            d.dishIngredients.filter { !it.deleted }.sortedBy { it.position }.map { i ->
                listOf(dishesById[i.dishId]?.name ?: "", ingType(i.refType), if (i.refType == RefType.BATCH) "" else refName(i.refType, i.refId), i.unit, n(i.qty), if (i.refType == RefType.BATCH) i.refId else "", i.id, i.dishId, i.refId, i.position.toString())
            },
        )
        val batches = Csv.write(
            listOf("batch_id", "dish_name", "batch_date", "servings", "final_qty", "final_unit", "yield_source", "total_calories", "total_protein", "total_fiber", "notes", "total_carbs", "total_fat", "finished", "dish_id"),
            d.batches.filter { !it.deleted }.map { b ->
                listOf(b.id, b.dishName, b.date, n(b.servings), n(b.finalQty), b.finalUnit ?: "", b.yieldSource, n(b.totalKcal), n(b.totalProtein), n(b.totalFibre), b.notes, n(b.totalCarbs), n(b.totalFat), b.finished.toString(), b.dishId ?: "")
            },
        )
        val batchIngs = Csv.write(
            listOf("batch_id", "ingredient_type", "ingredient_food_name", "ingredient_unit", "ingredient_qty", "ingredient_batch_id", "id", "ref_id", "position"),
            d.batchIngredients.filter { !it.deleted }.sortedBy { it.position }.map { i ->
                listOf(i.batchId, ingType(i.refType), if (i.refType == RefType.BATCH) "" else refName(i.refType, i.refId), i.unit, n(i.qty), if (i.refType == RefType.BATCH) i.refId else "", i.id, i.refId, i.position.toString())
            },
        )
        val logs = Csv.write(
            listOf("log_id", "date", "meal", "type", "name", "batch_id", "unit", "qty", "calories", "protein", "fiber", "checked", "carbs", "fat", "status", "ref_id", "source", "created_at", "basis", "variant", "note"),
            d.logs.filter { !it.deleted }.sortedWith(compareBy({ it.date }, { it.createdAt })).map { l ->
                listOf(l.id, l.date, l.meal, l.refType, l.name, if (l.refType == RefType.BATCH) l.refId ?: "" else "", l.unit, n(l.qty), n(l.kcal), n(l.protein), n(l.fibre), (l.status == EntryStatus.EATEN).toString().replaceFirstChar { it.uppercase() }, n(l.carbs), n(l.fat), l.status, l.refId ?: "", l.source, l.createdAt.toString(), l.basis, l.variant ?: "", l.note ?: "")
            },
        )
        val goals = Csv.write(
            listOf("date", "calorie_goal", "protein_goal", "fiber_goal"),
            d.goals.filter { !it.deleted }.sortedBy { it.effectiveFrom }.map { g -> listOf(g.effectiveFrom, n(g.kcal), n(g.protein), n(g.fibre)) },
        )
        val portions = Csv.write(
            listOf("id", "food_id", "label", "qty"),
            d.portions.filter { !it.deleted }.map { listOf(it.id, it.foodId, it.label, n(it.qty)) },
        )
        val weights = Csv.write(
            listOf("date", "kg"),
            d.weights.filter { !it.deleted }.sortedBy { it.date }.map { listOf(it.date, n(it.kg)) },
        )
        return linkedMapOf(
            "foods.csv" to foods, "food_portions.csv" to portions, "dishes.csv" to dishes, "dish_ingredients.csv" to dishIngs,
            "batches.csv" to batches, "batch_ingredients.csv" to batchIngs, "logs.csv" to logs, "goals.csv" to goals,
            "weights.csv" to weights,
        )
    }

    private fun n(v: Double?): String = when {
        v == null -> ""
        v == Math.floor(v) && kotlin.math.abs(v) < 1e15 -> String.format(Locale.US, "%.1f", v)
        else -> v.toString()
    }

    private fun Map<String, String>.text(col: String): String? = this[col]?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "nan" }
    private fun Map<String, String>.num(col: String): Double? = text(col)?.toDoubleOrNull()?.takeIf { !it.isNaN() }
}

/** Minimal RFC 4180 CSV reader/writer (quoted fields, embedded commas, quotes and newlines). */
object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val rows = parseRows(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
            header.mapIndexed { i, h -> h to (r.getOrNull(i) ?: "") }.toMap()
        }
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    fun write(header: List<String>, rows: List<List<String>>): String = buildString {
        append(header.joinToString(",") { quote(it) }).append('\n')
        rows.forEach { append(it.joinToString(",") { v -> quote(v) }).append('\n') }
    }

    private fun quote(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
