package app.shoku.domain

import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import java.util.UUID
import kotlin.math.round

/**
 * One-time grouping of the existing library into generic foods (a one-time pass, safe to repeat).
 *
 * - Produce: the existing "(gram)" row is promoted to the generic (same id, same numbers, new name),
 *   and the size rows ("(s)", "(m)", "(l)", "(clove)") are archived and become portions of it.
 *   Archived rows still resolve, so past entries, dishes and batches are untouched.
 * - Brands: a new generic is created with the median values of its products, and the products
 *   are linked to it with parentId. Products keep their own numbers.
 *
 * Ids are derived from names, so running this twice, or on two phones, gives the same rows.
 */
object FoodFamilies {

    private data class Promote(val from: String, val name: String, val aliases: String, val variants: List<String> = emptyList())

    private data class Family(
        val key: String,
        val name: String,
        val unit: String,
        val baseQty: Double,
        val aliases: String,
        val members: List<String>,
        /** Portions derived from a member's pack ("tin" from the tinned product), as label to member name. */
        val packPortions: List<Pair<String, String>> = emptyList(),
        /** Fixed household portions in the generic's unit, e.g. tbsp = 14 g. */
        val portions: List<Pair<String, Double>> = emptyList(),
    )

    private val PRODUCE = listOf(
        Promote("Banana (gram)", "Banana", "Banane", listOf("Banana (m)")),
        Promote("Apple (gram)", "Apple", "Apfel, Äpfel", listOf("Apple (s)", "Apple (m)", "Apple (l)")),
        Promote("Birne (gram)", "Pear", "Birne", listOf("Birne (m)")),
        Promote("Erdbeere (gram)", "Strawberry", "Erdbeere, Erdbeeren", listOf("Erdbeere (m)", "Erdbeere (l)")),
        Promote("Cucumber (gram)", "Cucumber", "Gurke, Salatgurke", listOf("Cucumber (s)", "Cucumber (m)", "Cucumber (l)")),
        Promote("Onion (gram)", "Onion", "Zwiebel, Zwiebeln", listOf("Onion (s)", "Onion (m)", "Onion (l)")),
        Promote("Orange (gram)", "Orange", "Apfelsine", listOf("Orange (s)", "Orange (m)", "Orange (l)")),
        Promote("Tomato (gram)", "Tomato", "Tomate, Tomaten", listOf("Tomato (s)", "Tomato (m)", "Tomato (l)")),
        Promote("Garlic (gram)", "Garlic", "Knoblauch", listOf("Garlic (clove)")),
        Promote("Boiled white Rice", "Cooked rice", "Reis gekocht, boiled rice, white rice, Reis"),
    )

    private val FAMILIES = listOf(
        Family("milk_whole", "Whole milk", "ml", 100.0, "Vollmilch, Milch, milk", listOf("Milbona Vollmilch"), portions = listOf("glass" to 250.0)),
        Family("milk_lowfat", "Low-fat milk", "ml", 100.0, "Fettarme Milch, Milch 1.5%, milk", listOf("Milbona Fettarme Milch"), portions = listOf("glass" to 250.0)),
        Family("muesli_plain", "Müsli, plain", "g", 100.0, "Muesli, Musli, Müsli, Kornmix", listOf("Bio Basis Müsli Cerealienmix", "Bio Basis Müsli Kornmix")),
        Family(
            "muesli_choc", "Chocolate crunchy müsli", "g", 100.0, "Schoko Müsli, Knusper Müsli, chocolate muesli, crunchy",
            listOf("Bio Klassik Müsli Dinkel & Schoko", "Bio Knupser Müsli Schoko", "Bio Knusper Müsli Schoko Banane"),
        ),
        Family(
            "whey", "Whey protein", "g", 100.0, "Protein powder, Proteinpulver, Whey",
            listOf("Accelerate Whey Protein Double Chocolate", "Accelerate Whey Protein Vanilla", "BodyLab24 Whey Protein Chocolate Brownie"),
            portions = listOf("scoop" to 30.0),
        ),
        Family("toast", "Toast bread (slice)", "pc", 1.0, "Toast, Toastbrot, Weizentoast, bread, slice", listOf("Grafschafter Weizen Toast")),
        Family(
            "crisps", "Crisps", "g", 100.0, "Chips, Kartoffelchips, potato chips",
            listOf("Bravo Paprika Chips Penny", "Snack Day Chips Paprika", "Lays Red Paprika", "Lay's Salt and Vinegar"),
        ),
        Family(
            "frozen_veg", "Frozen mixed vegetables", "g", 100.0, "Gemüsemischung, TK Gemüse, Tiefkühlgemüse, mixed veg",
            listOf("Freshona Kaisergemüse", "Freshona Zarte Gemüsemischung", "Freshona Bio Sommer Gemüse", "Freshona Bio Pfannengemüse Französisch"),
        ),
        Family(
            "kidney_beans", "Kidney beans, cooked", "g", 100.0, "Rajma, Kidneybohnen, Kidney Bohnen",
            listOf("Freshcon Cooked Rajma Kidney Beans", "Lidl Freshona Kidney Bohnen"),
            packPortions = listOf("tin" to "Lidl Freshona Kidney Bohnen"),
        ),
        Family(
            "pizza", "Frozen pizza", "g", 100.0, "Pizza, Tiefkühlpizza, Steinofenpizza",
            listOf("Alfredo Steinofen Pizza Morzzarella", "Alfredo Steinofen Pizza Margherita"),
            packPortions = listOf("whole" to "Alfredo Steinofen Pizza Margherita"),
        ),
        Family("iced_tea", "Iced tea", "ml", 100.0, "Eistee, FuzeTea, ice tea", listOf("FuzeTea Pfirsich", "FuzeTea Erdbeere Melone"), portions = listOf("bottle" to 400.0)),
        Family("beer", "Beer (lager)", "ml", 100.0, "Bier, Helles, lager", listOf("Löwenbräu Münchner Hell Beer", "Schultenbräu Helles"), portions = listOf("bottle" to 500.0, "small bottle" to 330.0)),
        Family("juice", "Fruit juice", "ml", 100.0, "Saft, Apfelsaft, Fruchtsaft, juice", listOf("Valensina Milder Apfel Saft", "Valensina Orange Mango Ananas Banana Apfel Juice"), portions = listOf("glass" to 200.0)),
        Family("choc_spread", "Chocolate spread", "g", 100.0, "Nutella, Nuss-Nougat-Creme, Schokocreme", listOf("Nutella", "MisterCHOC Duo Schoko Creme"), portions = listOf("tbsp" to 15.0)),
        Family("oil", "Cooking oil", "g", 100.0, "Öl, Oel, Sonnenblumenöl, oil", listOf("Bellasan Sonnenblumen Öl"), portions = listOf("tsp" to 5.0, "tbsp" to 14.0)),
        Family("ghee", "Ghee", "g", 100.0, "Butterschmalz, clarified butter", listOf("Khanum Ghee"), portions = listOf("tsp" to 5.0, "tbsp" to 14.0)),
        Family("butter", "Butter", "g", 100.0, "Markenbutter", listOf("Milsani Deutsche Markenbutter"), portions = listOf("tsp" to 5.0)),
        Family("skyr", "Skyr", "g", 100.0, "Skyr natur, Quark", listOf("Milbona SKYR Natur")),
        Family("yogurt", "Plain yogurt", "g", 100.0, "Joghurt, Naturjoghurt, curd, dahi", listOf("Milbona Joghurt mild cremig gerührt")),
        Family("ketchup", "Ketchup", "g", 100.0, "Tomatenketchup", listOf("Heinz Tomato Ketchup"), portions = listOf("tbsp" to 15.0)),
        Family("mayo", "Mayonnaise", "g", 100.0, "Mayo", listOf("Heinz Mayonnaise Das Original"), portions = listOf("tbsp" to 15.0)),
        Family("basmati", "Basmati rice, raw", "g", 100.0, "Reis, Basmati, rice uncooked", listOf("Bon-Ri Basmati Reis")),
        Family("oats", "Oats", "g", 100.0, "Haferflocken, Haferlocken, porridge oats", listOf("Crownfield Zarte Haferlocken")),
        Family("sugar", "Sugar", "g", 100.0, "Zucker", listOf("Diadem Kristall Zucker"), portions = listOf("tsp" to 4.0)),
        Family("chicken_breast", "Chicken breast fillet", "g", 100.0, "Hähnchenbrust, Hähnchenfilet, Hähnchen, chicken", listOf("Lidl Frische Hähnchen-Innenfilets")),
    )

    data class Result(val foods: List<Food>, val portions: List<FoodPortion>, val notes: List<String>) {
        val isEmpty get() = foods.isEmpty() && portions.isEmpty()
    }

    fun genericId(key: String): String = UUID.nameUUIDFromBytes("generic|$key".toByteArray()).toString()
    fun portionId(foodId: String, label: String): String = UUID.nameUUIDFromBytes("portion|$foodId|$label".toByteArray()).toString()

    /**
     * The old "Apple (m)" rows that became units of their family: kept (archived) so past entries still point
     * somewhere, but not foods you archived, so they're not listed, searched or offered anywhere.
     */
    fun isHiddenSizeRow(f: app.shoku.data.db.Food): Boolean = f.archived && f.parentId != null && sizeLabel(f.name) != null

    /** "(s)" -> small, "(m)" -> medium, "(l)" -> large, "(clove)" -> clove. */
    fun sizeLabel(variantName: String): String? = when (Regex("""\(([a-z]+)\)\s*$""").find(variantName)?.groupValues?.get(1)) {
        "s" -> "small"
        "m" -> "medium"
        "l" -> "large"
        "clove" -> "clove"
        else -> null
    }

    /** Works out the rows to write. Returns only rows that are new or changed. */
    fun plan(allFoods: List<Food>, existingPortions: List<FoodPortion>, now: Long = System.currentTimeMillis()): Result {
        val live = allFoods.filter { !it.deleted }
        val byName = live.groupBy { it.name }
        val changed = LinkedHashMap<String, Food>()
        val portions = LinkedHashMap<String, FoodPortion>()
        val portionIds = existingPortions.map { it.id }.toSet()
        val notes = mutableListOf<String>()
        fun current(f: Food) = changed[f.id] ?: f
        fun one(name: String): Food? = byName[name]?.singleOrNull().also { if (it == null) notes += "not found: $name" }
        fun addPortion(foodId: String, label: String, qty: Double) {
            val id = portionId(foodId, label)
            if (id !in portionIds && qty > 0) portions[id] = FoodPortion(id, foodId, label, qty, updatedAt = now)
        }

        for (p in PRODUCE) {
            val gen = byName[p.name]?.firstOrNull { it.kind == FoodKind.GENERIC } ?: one(p.from) ?: continue
            if (gen.kind != FoodKind.GENERIC || gen.name != p.name) {
                changed[gen.id] = current(gen).copy(name = p.name, kind = FoodKind.GENERIC, aliases = p.aliases, archived = false, updatedAt = now)
            }
            val perUnitKcal = gen.perUnit().kcal
            for (vName in p.variants) {
                val v = byName[vName]?.singleOrNull() ?: continue // already handled or missing
                val label = sizeLabel(vName) ?: continue
                // Portion weight that gives the same calories as the old size row.
                if (perUnitKcal != null && perUnitKcal > 0 && v.kcal != null) {
                    val grams = v.perUnit().kcal!! / perUnitKcal
                    addPortion(gen.id, label, if (grams < 10) round(grams * 10) / 10 else round(grams))
                }
                if (!v.archived || v.parentId != gen.id) changed[v.id] = current(v).copy(archived = true, parentId = gen.id, updatedAt = now)
            }
        }

        for (fam in FAMILIES) {
            val id = genericId(fam.key)
            val members = fam.members.mapNotNull(::one)
            if (members.isEmpty()) continue
            val existing = live.firstOrNull { it.id == id }
            val generic = existing ?: run {
                // Typical values = median of the members that use the generic's unit, scaled to its base amount.
                val same = members.filter { it.unit == fam.unit }.map { it.perUnit() * fam.baseQty }
                Food(
                    id = id, name = fam.name, unit = fam.unit, baseQty = fam.baseQty,
                    kcal = median(same.mapNotNull { it.kcal }), protein = median(same.mapNotNull { it.protein }),
                    fibre = median(same.mapNotNull { it.fibre }), carbs = median(same.mapNotNull { it.carbs }), fat = median(same.mapNotNull { it.fat }),
                    kind = FoodKind.GENERIC, aliases = fam.aliases, source = "generic", updatedAt = now,
                ).also { changed[id] = it }
            }
            for (m in members) if (m.parentId == null) changed[m.id] = current(m).copy(parentId = generic.id, updatedAt = now)
            for ((label, qty) in fam.portions) addPortion(generic.id, label, qty)
            val perUnitKcal = generic.perUnit().kcal
            for ((label, memberName) in fam.packPortions) {
                val m = members.firstOrNull { it.name == memberName } ?: continue
                if (perUnitKcal != null && perUnitKcal > 0 && m.kcal != null) addPortion(generic.id, label, round(m.perUnit().kcal!! / perUnitKcal))
            }
        }
        return Result(changed.values.toList(), portions.values.toList(), notes)
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
