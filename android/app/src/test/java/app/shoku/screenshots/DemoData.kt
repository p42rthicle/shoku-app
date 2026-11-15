package app.shoku.screenshots

import app.shoku.ShokuApplication
import app.shoku.data.ai.AiSettings
import app.shoku.data.db.Basis
import app.shoku.data.db.Batch
import app.shoku.data.db.Dish
import app.shoku.data.db.DishIngredient
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.LogEntry
import app.shoku.data.db.MealTemplate
import app.shoku.data.db.Meals
import app.shoku.data.db.RefType
import app.shoku.data.db.Slots
import app.shoku.data.db.StockItem
import app.shoku.data.db.StockPlaces
import app.shoku.data.db.Supplement
import app.shoku.data.db.SupplementDose
import app.shoku.data.db.TemplateItem
import app.shoku.domain.IngredientRef
import app.shoku.domain.Library
import app.shoku.domain.PortionMetrics.Companion.SERVING
import app.shoku.domain.SLOT
import app.shoku.domain.ScheduleRule
import app.shoku.domain.encodeSchedule
import app.shoku.domain.isDue
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Made-up demo data for the screenshots: one ordinary week and a bit of somebody who cooks at home.
 * Generic foods and invented brands only; nothing here comes from a real person's log.
 * Seeded through the app's own DAOs and repositories, so every number is computed the way the app does it.
 */
object DemoData {
    private val today: LocalDate = LocalDate.now()
    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- Foods (per 100 g / 100 ml unless the unit is pc) ----------

    private class F(
        val id: String, val name: String, val unit: String, val base: Double,
        val kcal: Double, val protein: Double, val fibre: Double, val carbs: Double, val fat: Double,
        val kind: String = FoodKind.GENERIC, val brand: String? = null, val aliases: String = "",
        val portions: List<Pair<String, Double>> = emptyList(), val barcode: String? = null,
    )

    private val foods = listOf(
        F("toor-dal", "Toor dal", "g", 100.0, 343.0, 22.3, 15.0, 57.6, 1.7, aliases = "arhar dal, pigeon peas"),
        F("moong-dal", "Moong dal", "g", 100.0, 348.0, 24.5, 8.2, 59.9, 1.2, aliases = "yellow lentils"),
        F("rajma", "Rajma", "g", 100.0, 333.0, 23.6, 15.2, 60.0, 0.8, aliases = "kidney beans"),
        F("rice", "Steamed rice", "g", 100.0, 130.0, 2.7, 0.4, 28.2, 0.3, aliases = "chawal, basmati", portions = listOf("bowl" to 150.0)),
        F("roti", "Roti", "pc", 1.0, 112.0, 3.6, 2.6, 18.5, 2.9, aliases = "chapati, phulka"),
        F("paneer", "Paneer", "g", 100.0, 265.0, 18.3, 0.0, 1.2, 20.8, portions = listOf("cube" to 20.0)),
        F("curd", "Curd", "g", 100.0, 61.0, 3.5, 0.0, 4.7, 3.3, aliases = "dahi, yogurt", portions = listOf("bowl" to 150.0)),
        F("egg", "Egg", "pc", 1.0, 72.0, 6.3, 0.0, 0.4, 4.8, aliases = "anda"),
        F("chicken", "Chicken breast", "g", 100.0, 120.0, 22.5, 0.0, 0.0, 2.6),
        F("oats", "Rolled oats", "g", 100.0, 379.0, 13.2, 10.1, 67.7, 6.5, portions = listOf("cup" to 80.0)),
        F("banana", "Banana", "g", 100.0, 89.0, 1.1, 2.6, 22.8, 0.3, aliases = "kela", portions = listOf("medium" to 118.0)),
        F("apple", "Apple", "g", 100.0, 52.0, 0.3, 2.4, 13.8, 0.2, portions = listOf("medium" to 180.0)),
        F("milk", "Toned milk", "ml", 100.0, 58.0, 3.2, 0.0, 4.8, 3.0, aliases = "doodh", portions = listOf("cup" to 150.0, "glass" to 250.0)),
        F("coffee", "Filter coffee", "ml", 100.0, 45.0, 1.6, 0.0, 5.6, 1.6, portions = listOf("cup" to 150.0)),
        F("chai", "Masala chai", "ml", 100.0, 50.0, 1.6, 0.0, 7.0, 1.6, aliases = "tea", portions = listOf("cup" to 150.0)),
        F("onion", "Onion", "g", 100.0, 40.0, 1.1, 1.7, 9.3, 0.1, aliases = "pyaz"),
        F("tomato", "Tomato", "g", 100.0, 18.0, 0.9, 1.2, 3.9, 0.2, aliases = "tamatar"),
        F("spinach", "Spinach", "g", 100.0, 23.0, 2.9, 2.2, 3.6, 0.4, aliases = "palak"),
        F("potato", "Potato", "g", 100.0, 77.0, 2.0, 2.2, 17.0, 0.1, aliases = "aloo"),
        F("cauliflower", "Cauliflower", "g", 100.0, 25.0, 1.9, 2.0, 5.0, 0.3, aliases = "gobi"),
        F("okra", "Okra", "g", 100.0, 33.0, 1.9, 3.2, 7.5, 0.2, aliases = "bhindi"),
        F("cucumber", "Cucumber", "g", 100.0, 15.0, 0.7, 0.5, 3.6, 0.1, aliases = "kheera"),
        F("carrot", "Carrot", "g", 100.0, 41.0, 0.9, 2.8, 9.6, 0.2, aliases = "gajar"),
        F("ghee", "Ghee", "g", 100.0, 900.0, 0.0, 0.0, 0.0, 100.0, portions = listOf("tsp" to 5.0, "tbsp" to 14.0)),
        F("oil", "Sunflower oil", "g", 100.0, 884.0, 0.0, 0.0, 0.0, 100.0, portions = listOf("tbsp" to 14.0)),
        F("peanuts", "Roasted peanuts", "g", 100.0, 585.0, 24.0, 8.5, 21.0, 50.0, portions = listOf("handful" to 30.0)),
        F("almonds", "Almonds", "g", 100.0, 579.0, 21.0, 12.5, 21.6, 49.9, aliases = "badam", portions = listOf("handful" to 25.0)),
        F("poha", "Poha", "g", 100.0, 346.0, 6.6, 1.0, 77.0, 1.2, aliases = "flattened rice"),
        F("honey", "Honey", "g", 100.0, 304.0, 0.3, 0.2, 82.0, 0.0, portions = listOf("tsp" to 7.0)),
        F("idli", "Idli", "pc", 1.0, 58.0, 2.0, 0.8, 12.0, 0.2, kind = FoodKind.HOMEMADE),
        F("sambar", "Sambar", "g", 100.0, 65.0, 3.2, 2.1, 9.0, 1.8, kind = FoodKind.HOMEMADE, portions = listOf("bowl" to 200.0)),
        F("turmeric", "Turmeric", "g", 100.0, 312.0, 9.7, 22.7, 67.1, 3.3, aliases = "haldi"),
        // Packaged, with invented brands.
        F("masala-peanuts", "Masala peanuts", "g", 100.0, 540.0, 20.0, 6.0, 30.0, 38.0, FoodKind.PRODUCT, "Crunchwala", portions = listOf("pack" to 40.0), barcode = DEMO_BARCODE),
        F("makhana", "Roasted makhana, peri peri", "g", 100.0, 470.0, 9.5, 7.0, 60.0, 21.0, FoodKind.PRODUCT, "Lotus Leaf", "fox nuts", portions = listOf("pack" to 30.0)),
        F("bread", "Whole wheat bread", "g", 100.0, 245.0, 9.8, 6.4, 43.0, 2.9, FoodKind.PRODUCT, "Morning Mill", portions = listOf("slice" to 27.0)),
        F("protein-bar", "Protein bar, peanut cocoa", "pc", 1.0, 230.0, 20.0, 6.0, 22.0, 8.0, FoodKind.PRODUCT, "Strive"),
        F("biscuits", "Glucose biscuits", "g", 100.0, 450.0, 7.0, 1.5, 76.0, 13.0, FoodKind.PRODUCT, "Sunny Bake", portions = listOf("biscuit" to 7.5)),
        F("hp-curd", "High-protein curd", "g", 100.0, 85.0, 10.0, 0.0, 5.5, 2.5, FoodKind.PRODUCT, "Kesar Farms", "skyr, dahi", portions = listOf("cup" to 200.0)),
        F("masala-oats", "Masala oats", "g", 100.0, 380.0, 11.0, 9.0, 64.0, 8.0, FoodKind.PRODUCT, "Morning Mill", portions = listOf("pack" to 40.0)),
        F("peanut-butter", "Peanut butter, crunchy", "g", 100.0, 600.0, 26.0, 7.0, 18.0, 48.0, FoodKind.PRODUCT, "Crunchwala", portions = listOf("tbsp" to 16.0)),
    )

    // ---------- Dishes: ingredients are for the whole pot ----------

    private class D(val id: String, val name: String, val servings: Double, val yieldG: Double?, val items: List<Triple<String, String, Double>>)

    private val dishes = listOf(
        D("dal-tadka", "Dal tadka", 4.0, 1200.0, listOf(Triple("toor-dal", "g", 200.0), Triple("onion", "g", 100.0), Triple("tomato", "g", 150.0), Triple("ghee", "tbsp", 1.0), Triple("turmeric", "g", 3.0))),
        D("rajma-masala", "Rajma masala", 5.0, 1400.0, listOf(Triple("rajma", "g", 250.0), Triple("onion", "g", 150.0), Triple("tomato", "g", 200.0), Triple("oil", "tbsp", 1.5))),
        D("palak-paneer", "Palak paneer", 4.0, 900.0, listOf(Triple("spinach", "g", 500.0), Triple("paneer", "g", 250.0), Triple("onion", "g", 100.0), Triple("tomato", "g", 100.0), Triple("oil", "tbsp", 1.0))),
        D("chicken-curry", "Chicken curry", 4.0, 1100.0, listOf(Triple("chicken", "g", 600.0), Triple("onion", "g", 200.0), Triple("tomato", "g", 200.0), Triple("curd", "g", 100.0), Triple("oil", "tbsp", 2.0))),
        D("aloo-gobi", "Aloo gobi", 4.0, 800.0, listOf(Triple("potato", "g", 300.0), Triple("cauliflower", "g", 400.0), Triple("onion", "g", 100.0), Triple("tomato", "g", 100.0), Triple("oil", "tbsp", 1.5))),
        D("overnight-oats", "Overnight oats", 1.0, null, listOf(Triple("oats", "g", 50.0), Triple("milk", "ml", 150.0), Triple("hp-curd", "g", 100.0), Triple("banana", "medium", 1.0), Triple("honey", "tsp", 1.0))),
    )

    /** A made-up barcode (not a real product) on the demo's Masala peanuts, for the scan screenshot. */
    const val DEMO_BARCODE = "8900000000017"
    const val FRIDGE_BATCH = "batch-dal"
    const val FINISHED_BATCH = "batch-rajma"

    suspend fun seed(app: ShokuApplication) {
        val db = app.database
        val lib = db.library()
        val now = System.currentTimeMillis()

        lib.upsertFoods(foods.map { f ->
            Food(f.id, f.name, f.unit, f.base, f.kcal, f.protein, f.fibre, f.carbs, f.fat, brand = f.brand, barcode = f.barcode, kind = f.kind, aliases = f.aliases, updatedAt = now)
        })
        lib.upsertPortions(foods.flatMap { f -> f.portions.map { (label, qty) -> FoodPortion("${f.id}-$label", f.id, label, qty, now) } })
        lib.upsertDishes(dishes.map { Dish(it.id, it.name, fixed = false, servings = it.servings, yieldQty = it.yieldG, yieldUnit = it.yieldG?.let { "g" }, updatedAt = now) })
        lib.upsertDishIngredients(dishes.flatMap { d ->
            d.items.mapIndexed { i, (food, unit, qty) -> DishIngredient("${d.id}-$i", d.id, RefType.FOOD, food, unit, qty, i, now) }
        })

        // Two cooks: rajma last week (eaten up), dal yesterday (half left, in the fridge).
        fun refs(dishId: String) = dishes.first { it.id == dishId }.items.map { (food, unit, qty) -> IngredientRef(RefType.FOOD, food, unit, qty) }
        app.repository.saveBatch(
            Batch(FINISHED_BATCH, "rajma-masala", "Rajma masala", today.minusDays(6).toString(), 5.0, 1400.0, "g", totalKcal = null, totalProtein = null, totalFibre = null, finished = true, notes = "Soaked overnight"),
            refs("rajma-masala"),
        )
        app.repository.saveBatch(
            Batch(FRIDGE_BATCH, "dal-tadka", "Dal tadka", today.minusDays(1).toString(), 4.0, 1200.0, "g", totalKcal = null, totalProtein = null, totalFibre = null, notes = "Extra garlic in the tadka"),
            refs("dal-tadka"),
        )

        app.repository.setGoal(today.minusDays(60), kcal = 1800.0, protein = 110.0, fibre = 30.0)

        val library = app.repository.library.first { it.batches.size == 2 }
        db.logs().upsertAll(history(library) + todayLog(library))

        listOf(14L to 74.6, 11L to 74.4, 7L to 74.1, 4L to 73.9, 0L to 73.7).forEach { (ago, kg) -> app.repository.setWeight(today.minusDays(ago), kg) }

        seedSupplements(app)
        seedTemplates(app)
        seedStock(app)

        // A little AI use this month, for the Settings card (the ledger outlives a single test, so only once).
        val ledger = AiSettings.ledger
        if (ledger.usage().calls == 0) repeat(37) { ledger.add(0.0227) }
    }

    // ---------- The log ----------

    /** One logged item: what, in which unit and how much. */
    private data class I(val type: String, val id: String, val unit: String, val qty: Double)

    private fun food(id: String, unit: String, qty: Double) = I(RefType.FOOD, id, unit, qty)
    private fun dish(id: String, qty: Double = 1.0) = I(RefType.DISH, id, SERVING, qty)
    private fun batch(id: String, qty: Double = 1.0) = I(RefType.BATCH, id, SERVING, qty)

    private val breakfasts = listOf(
        listOf(dish("overnight-oats"), food("coffee", "cup", 1.0)),
        listOf(food("egg", "pc", 3.0), food("bread", "slice", 2.0), food("chai", "cup", 1.0)),
        listOf(food("poha", "g", 60.0), food("peanuts", "g", 10.0), food("hp-curd", "cup", 1.0), food("chai", "cup", 1.0)),
        listOf(food("idli", "pc", 3.0), food("sambar", "bowl", 1.0), food("egg", "pc", 2.0), food("coffee", "cup", 1.0)),
        listOf(food("hp-curd", "cup", 1.0), food("banana", "medium", 1.0), food("almonds", "handful", 1.0)),
    )
    private val lunches = listOf(
        listOf(dish("dal-tadka"), food("rice", "bowl", 1.0), food("curd", "bowl", 1.0)),
        listOf(dish("aloo-gobi"), food("roti", "pc", 2.0), food("curd", "bowl", 1.0)),
        listOf(dish("chicken-curry"), food("rice", "bowl", 1.0), food("cucumber", "g", 100.0)),
        listOf(dish("palak-paneer"), food("roti", "pc", 2.0)),
    )
    private val snacks = listOf(
        listOf(food("apple", "medium", 1.0), food("chai", "cup", 1.0)),
        listOf(food("masala-peanuts", "pack", 1.0)),
        listOf(food("protein-bar", "pc", 1.0)),
        listOf(food("banana", "medium", 1.0), food("almonds", "handful", 1.0)),
        listOf(food("makhana", "pack", 1.0), food("chai", "cup", 1.0)),
        listOf(food("hp-curd", "cup", 1.0)),
    )
    private val dinners = listOf(
        listOf(dish("palak-paneer"), food("roti", "pc", 2.0), food("cucumber", "g", 100.0)),
        listOf(dish("chicken-curry"), food("roti", "pc", 2.0)),
        listOf(food("chicken", "g", 250.0), food("rice", "bowl", 1.0), food("carrot", "g", 80.0)),
        listOf(dish("aloo-gobi"), food("roti", "pc", 2.0), food("curd", "bowl", 1.0)),
        listOf(food("egg", "pc", 3.0), food("roti", "pc", 2.0), food("okra", "g", 150.0), food("oil", "tbsp", 0.5)),
    )

    private fun entries(library: Library, date: LocalDate, meal: String, items: List<I>, status: String = EntryStatus.EATEN): List<LogEntry> {
        val at = date.atTime(LocalTime.of(when (meal) { Meals.BREAKFAST -> 8; Meals.LUNCH -> 13; Meals.SNACKS -> 17; else -> 20 }, 15)).atZone(zone).toInstant().toEpochMilli()
        return items.mapIndexed { i, it ->
            val n = library.entryNutrients(it.type, it.id, it.unit, it.qty) ?: error("No nutrients for ${it.id} in ${it.unit}")
            val f = if (it.type == RefType.FOOD) library.foods[it.id] else null
            LogEntry(
                id = "e-$date-$meal-$i", date = date.toString(), meal = meal, refType = it.type, refId = it.id,
                name = library.nameOf(it.type, it.id)!!, unit = it.unit, qty = it.qty,
                kcal = n.kcal, protein = n.protein, fibre = n.fibre, carbs = n.carbs, fat = n.fat,
                status = status, basis = if (f?.kind == FoodKind.GENERIC) Basis.TYPICAL else Basis.EXACT,
                createdAt = at + i, updatedAt = at + i,
            )
        }
    }

    /** Two weeks before today. The rajma batch feeds days 6 to 4 back; yesterday's dinner is the new dal. */
    private fun history(library: Library): List<LogEntry> {
        val rnd = Random(7)
        return (1L..14L).flatMap { ago ->
            val d = today.minusDays(ago)
            val lunch = when (ago) {
                6L, 5L, 4L -> listOf(batch(FINISHED_BATCH), food("rice", "bowl", 1.0))
                else -> lunches[rnd.nextInt(lunches.size)]
            }
            val dinner = when (ago) {
                1L -> listOf(batch(FRIDGE_BATCH), food("roti", "pc", 2.0), food("curd", "bowl", 1.0))
                5L -> listOf(batch(FINISHED_BATCH, 2.0), food("roti", "pc", 1.0))
                else -> dinners[rnd.nextInt(dinners.size)]
            }
            buildList {
                addAll(entries(library, d, Meals.BREAKFAST, breakfasts[rnd.nextInt(breakfasts.size)]))
                addAll(entries(library, d, Meals.LUNCH, lunch))
                if (ago % 5 != 3L) addAll(entries(library, d, Meals.SNACKS, snacks[rnd.nextInt(snacks.size)]))
                addAll(entries(library, d, Meals.DINNER, dinner))
            }
        }
    }

    /** Breakfast and lunch eaten; the snack and dinner still planned. */
    private fun todayLog(library: Library): List<LogEntry> =
        entries(library, today, Meals.BREAKFAST, listOf(dish("overnight-oats"), food("coffee", "cup", 1.0))) +
            entries(library, today, Meals.LUNCH, listOf(batch(FRIDGE_BATCH), food("rice", "bowl", 1.0), food("curd", "bowl", 1.0))) +
            entries(library, today, Meals.SNACKS, listOf(food("apple", "medium", 1.0), food("chai", "cup", 1.0)), EntryStatus.PLANNED) +
            entries(library, today, Meals.DINNER, listOf(dish("palak-paneer"), food("roti", "pc", 2.0), food("cucumber", "g", 100.0)), EntryStatus.PLANNED)

    // ---------- Supplements ----------

    private suspend fun seedSupplements(app: ShokuApplication) {
        val start = today.minusDays(40).toString()
        val supps = listOf(
            Supplement("supp-d", "Vitamin D3", "60,000 IU", unit = "capsule", slot = Meals.BREAKFAST,
                schedule = encodeSchedule(listOf(ScheduleRule(ScheduleRule.WEEK, day = "SUN"))), startDate = start,
                howTo = "With a meal that has some fat", position = 0),
            Supplement("supp-omega", "Omega-3", "1 g fish oil", unit = "capsule", slot = Meals.LUNCH,
                schedule = encodeSchedule(listOf(ScheduleRule(ScheduleRule.DAY))), startDate = start,
                howTo = "With lunch", position = 1),
            Supplement("supp-mg", "Magnesium", "Glycinate, 200 mg", unit = "tablet", slot = Slots.BEDTIME,
                schedule = encodeSchedule(listOf(ScheduleRule(ScheduleRule.DAY))), startDate = start, position = 2),
        )
        supps.forEach { app.home.saveSupplement(it) }
        app.home.countSupplement(supps[0], 6.0)
        app.home.countSupplement(supps[1], 42.0)
        app.home.countSupplement(supps[2], 9.0)

        // Taken most days of the last two weeks (a couple of evenings forgotten), and today's lunch dose.
        val doses = (0L..14L).flatMap { ago ->
            val d = today.minusDays(ago)
            val t = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() - 86_400_000L
            supps.filter { s -> s.isDue(d) }
                .filter { s -> !(s.id == "supp-mg" && (ago == 0L || ago == 3L || ago == 9L)) }
                .filter { s -> !(ago == 0L && s.slot != Meals.LUNCH && s.slot != Meals.BREAKFAST) }
                .map { s -> SupplementDose("${s.id}|$d", s.id, d.toString(), s.dose, taken = true, updatedAt = t) }
        }
        app.database.home().upsertDoses(doses)
    }

    // ---------- Meal templates and the week plan ----------

    private suspend fun seedTemplates(app: ShokuApplication) {
        fun item(id: String, type: String, ref: String?, unit: String, qty: Double, label: String = "") = TemplateItem(id, "", type, ref, label, unit, qty)
        val templates = listOf(
            MealTemplate("t-oats", "Overnight oats and coffee", Meals.BREAKFAST, 0) to listOf(
                item("t-oats-1", RefType.DISH, "overnight-oats", SERVING, 1.0), item("t-oats-2", RefType.FOOD, "coffee", "cup", 1.0),
            ),
            MealTemplate("t-eggs", "Eggs on toast", Meals.BREAKFAST, 1) to listOf(
                item("t-eggs-1", RefType.FOOD, "egg", "pc", 2.0), item("t-eggs-2", RefType.FOOD, "bread", "slice", 2.0), item("t-eggs-3", RefType.FOOD, "chai", "cup", 1.0),
            ),
            MealTemplate("t-lunch", "Dal, rice and curd", Meals.LUNCH, 2) to listOf(
                item("t-lunch-1", SLOT, null, SERVING, 1.0, "Sabzi or dal from the fridge"), item("t-lunch-2", RefType.FOOD, "rice", "bowl", 1.0), item("t-lunch-3", RefType.FOOD, "curd", "bowl", 1.0),
            ),
            MealTemplate("t-snack", "Fruit and curd", Meals.SNACKS, 3) to listOf(
                item("t-snack-1", RefType.FOOD, "hp-curd", "cup", 1.0), item("t-snack-2", RefType.FOOD, "apple", "medium", 1.0),
            ),
            MealTemplate("t-dinner", "Roti, sabzi and salad", Meals.DINNER, 4) to listOf(
                item("t-dinner-1", SLOT, null, SERVING, 1.0, "Sabzi or dal from the fridge"), item("t-dinner-2", RefType.FOOD, "roti", "pc", 2.0), item("t-dinner-3", RefType.FOOD, "cucumber", "g", 100.0),
            ),
        )
        templates.forEach { (t, items) -> app.home.saveTemplate(t, items) }
        val byId = templates.associate { it.first.id to it.first }
        app.home.setWeekdays(byId.getValue("t-oats"), setOf(1, 3, 5))
        app.home.setWeekdays(byId.getValue("t-eggs"), setOf(2, 4, 6, 7))
        app.home.setWeekdays(byId.getValue("t-lunch"), (1..7).toSet())
        app.home.setWeekdays(byId.getValue("t-snack"), (1..5).toSet())
        app.home.setWeekdays(byId.getValue("t-dinner"), (1..7).toSet())
    }

    // ---------- What's at home ----------

    private suspend fun seedStock(app: ShokuApplication) {
        val now = System.currentTimeMillis()
        fun tracked(id: String, food: String, name: String, unit: String, place: String, qty: Double, lowAt: Double? = null, bestBefore: LocalDate? = null) =
            StockItem("s-$id", name, foodId = food, unit = unit, place = place, countQty = qty, countedAt = now, lowAt = lowAt, bestBefore = bestBefore?.toString(), updatedAt = now)
        fun status(id: String, name: String, place: String, status: String) =
            StockItem("s-$id", name, unit = "g", place = place, tracked = false, status = status, countedAt = now, updatedAt = now)
        app.database.home().upsertStock(listOf(
            tracked("paneer", "paneer", "Paneer", "g", StockPlaces.FRIDGE, 200.0, 100.0, today.plusDays(3)),
            tracked("curd", "curd", "Curd", "g", StockPlaces.FRIDGE, 400.0, 150.0, today.plusDays(4)),
            tracked("hp-curd", "hp-curd", "High-protein curd", "g", StockPlaces.FRIDGE, 600.0, 200.0, today.plusDays(9)),
            tracked("milk", "milk", "Toned milk", "ml", StockPlaces.FRIDGE, 1000.0, 300.0, today.plusDays(2)),
            tracked("egg", "egg", "Eggs", "pc", StockPlaces.FRIDGE, 6.0, 4.0),
            tracked("spinach", "spinach", "Spinach", "g", StockPlaces.FRIDGE, 250.0, bestBefore = today.plusDays(1)),
            tracked("tomato", "tomato", "Tomatoes", "g", StockPlaces.FRIDGE, 500.0, 200.0),
            tracked("chicken", "chicken", "Chicken breast", "g", StockPlaces.FREEZER, 750.0, 250.0),
            tracked("toor-dal", "toor-dal", "Toor dal", "g", StockPlaces.PANTRY, 1500.0, 300.0),
            tracked("rajma", "rajma", "Rajma", "g", StockPlaces.PANTRY, 180.0, 250.0),
            tracked("oats", "oats", "Rolled oats", "g", StockPlaces.PANTRY, 750.0, 200.0),
            tracked("poha", "poha", "Poha", "g", StockPlaces.PANTRY, 400.0),
            tracked("almonds", "almonds", "Almonds", "g", StockPlaces.PANTRY, 200.0, 50.0),
            tracked("masala-peanuts", "masala-peanuts", "Masala peanuts", "g", StockPlaces.PANTRY, 80.0, 40.0),
            tracked("onion", "onion", "Onions", "g", StockPlaces.PANTRY, 1200.0, 300.0),
            status("rice", "Basmati rice", StockPlaces.PANTRY, "have"),
            status("turmeric", "Turmeric", StockPlaces.SPICES, "have"),
            status("garam-masala", "Garam masala", StockPlaces.SPICES, "low"),
            status("cumin", "Cumin seeds", StockPlaces.SPICES, "have"),
            status("chilli", "Red chilli powder", StockPlaces.SPICES, "out"),
        ))
    }
}
