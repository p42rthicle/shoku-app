package app.shoku.domain

import app.shoku.data.db.Food
import app.shoku.data.db.FoodKind
import app.shoku.data.db.FoodPortion
import app.shoku.data.db.LogEntry
import app.shoku.data.db.RefType

/**
 * Duplicate foods: two or more library rows for one product ("High-protein wrap · Goldähren" counted
 * per piece and "Protein Wraps · Goldähren" read from the label per 100 g). Found by barcode, or by
 * brand and name words with matching nutrition. Families (a generic "Butter" with its Milsani product,
 * or "Tomato (s/m/l)") are not duplicates.
 */
object Duplicates {

    /** What a food has going for it when choosing which one to keep. */
    data class Member(val food: Food, val logs: Int, val lastLogged: String?, val inStock: Boolean, val info: Int, val portions: Int)

    /** One product found more than once, with the suggested one to keep and the one whose values to use. */
    data class Group(val members: List<Member>, val keep: Member, val valuesFrom: Member) {
        val key get() = members.map { it.food.id }.sorted().joinToString("|")
        /** Why [keep] is suggested, in plain words. */
        val keepReason: String get() = reasons(keep, members)
        /** Why [valuesFrom]'s numbers are used, when it isn't [keep]. */
        val valuesReason: String? get() = if (valuesFrom.food.id == keep.food.id) null else infoWords(valuesFrom).joinToString(", ").ifEmpty { "more complete values" }
    }

    private val STOP = setOf("with", "and", "the", "of", "in", "a", "ready", "to", "eat", "pack", "small", "large", "big", "mini", "ohne", "mit", "und")
    /** Words that don't make a different product ("High-protein wrap" = "Protein Wraps"); flavours and ingredients do. */
    private val FILLER = setOf("high", "original", "classic", "plain", "fresh", "premium", "bio", "organic", "family", "value", "new", "ultra", "pure", "extra", "style", "type")
    private val BRAND_STOP = setOf("pvt", "ltd", "limited", "snacks", "foods", "food", "gmbh", "co", "inc", "company")

    private fun stem(w: String) = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
    fun nameWords(name: String): Set<String> =
        searchKey(name).replace(Regex("\\([a-z]+\\)\\s*$"), " ").split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.map(::stem).toSet()
    fun brandKey(brand: String?): String =
        searchKey(brand.orEmpty()).replace("'", "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in BRAND_STOP }.map(::stem).joinToString(" ")

    /** kcal per 1 g or 1 ml, or null when counted in pieces or unknown. */
    private fun density(f: Food): Double? = if (f.unit in WEIGHT_UNITS && f.baseQty > 0) f.kcal?.div(f.baseQty) else null

    private fun similarNutrition(a: Food, b: Food): Boolean {
        val da = density(a); val db = density(b)
        if (da == null || db == null) return true // pieces vs grams: can't compare, the names decide
        if (a.unit != b.unit) return false
        val diff = Math.abs(da - db) * 100
        return diff <= 8 || diff <= 0.15 * Math.max(da, db) * 100
    }

    private fun related(a: Food, b: Food): Boolean =
        a.parentId == b.id || b.parentId == a.id ||
            (a.parentId != null && a.parentId == b.parentId && (FoodFamilies.sizeLabel(a.name) != null || FoodFamilies.sizeLabel(b.name) != null))

    /** True when [a] and [b] look like the same product. */
    fun same(a: Food, b: Food): Boolean = same(a, b, Keys(a), Keys(b))

    private class Keys(f: Food) { val words = nameWords(f.name); val brand = brandKey(f.brand) }

    private fun same(a: Food, b: Food, ka: Keys, kb: Keys): Boolean {
        if (a.id == b.id || related(a, b)) return false
        if (!a.barcode.isNullOrBlank() && a.barcode == b.barcode) return true
        if (!a.barcode.isNullOrBlank() && !b.barcode.isNullOrBlank()) return false // two different barcodes: two products
        val ba = ka.brand; val bb = kb.brand
        val wa = ka.words; val wb = kb.words
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (!similarNutrition(a, b)) return false
        // A generic and a product of the same name are a family, not a duplicate.
        if ((a.kind == FoodKind.GENERIC) != (b.kind == FoodKind.GENERIC) && (ba.isEmpty() != bb.isEmpty())) return false
        return when {
            ba.isNotEmpty() && bb.isNotEmpty() && ba != bb -> false
            wa == wb -> true
            // Same brand and the names differ only by filler words: "Protein Wraps" / "High-protein wrap".
            // (Not "Chocolate banana crunchy müsli" / "Chocolate crunchy müsli": banana is a flavour.)
            ba.isNotEmpty() && ba == bb -> (wa - wb).all { it in FILLER } && (wb - wa).all { it in FILLER }
            else -> false
        }
    }

    private fun info(f: Food, portions: Int, inStock: Boolean): Int =
        listOf(f.kcal, f.protein, f.fibre, f.carbs, f.fat).count { it != null } +
            (if (!f.brand.isNullOrBlank()) 1 else 0) + (if (!f.barcode.isNullOrBlank()) 2 else 0) +
            (if (f.source in setOf("label", "off", "openfoodfacts")) 2 else 0) + Math.min(portions, 2) + (if (inStock) 1 else 0)

    private fun infoWords(m: Member): List<String> = listOfNotNull(
        when (m.food.source) { "label" -> "values from the label"; "off", "openfoodfacts" -> "values from Open Food Facts"; else -> null },
        if (!m.food.barcode.isNullOrBlank()) "barcode" else null,
        if (listOf(m.food.carbs, m.food.fat, m.food.fibre).all { it != null }) "full macros" else null,
        if (m.inStock) "in your stock" else null,
    )

    private fun reasons(keep: Member, all: List<Member>): String {
        val mostLogged = all.maxOf { it.logs }
        return listOfNotNull(
            if (keep.logs > 0 && keep.logs == mostLogged) "logged ${keep.logs}×" + (keep.lastLogged?.let { ", last ${java.time.LocalDate.parse(it).let { d -> "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}" }}" } ?: "") else null,
            infoWords(keep).joinToString(", ").ifEmpty { null },
        ).joinToString("; ").ifEmpty { "the oldest one" }
    }

    /** Every group of duplicates among live (and archived) foods, the ones you've logged first. */
    fun find(library: Library, logs: List<LogEntry>, stockFoodIds: Set<String>, ignored: Set<String> = emptySet()): List<Group> {
        val foods = library.foods.values.filter { !it.deleted }
        val used = logs.filter { !it.deleted && it.refType == RefType.FOOD && it.refId != null }.groupBy { it.refId!! }
        fun member(f: Food): Member {
            val p = library.portions[f.id].orEmpty().count { it.label != f.unit }
            val l = used[f.id].orEmpty()
            return Member(f, l.size, l.maxOfOrNull { it.date }, f.id in stockFoodIds, info(f, p, f.id in stockFoodIds), p)
        }
        // Union-find over matching pairs.
        val parent = HashMap<String, String>()
        fun root(x: String): String { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        val keys = foods.map(::Keys)
        for (i in foods.indices) for (j in i + 1 until foods.size) {
            if (same(foods[i], foods[j], keys[i], keys[j])) { val ra = root(foods[i].id); val rb = root(foods[j].id); if (ra != rb) parent[ra] = rb }
        }
        return foods.groupBy { root(it.id) }.values.filter { it.size > 1 }
            .map { fs -> fs.map(::member) }
            .map { ms ->
                val keep = ms.sortedWith(compareByDescending<Member> { it.logs }.thenBy { it.food.archived }.thenByDescending { it.info }).first()
                val values = ms.sortedWith(compareByDescending<Member> { it.info }.thenByDescending { it.logs }).first().let { if (it.info > keep.info) it else keep }
                Group(ms.sortedByDescending { it.logs }, keep, values)
            }
            .filter { it.key !in ignored }
            .sortedByDescending { g -> g.members.sumOf { it.logs } }
    }

    /** Library foods that [draft] probably is (for "Already in your library?"), best first. */
    fun matches(library: Library, draft: Food, logs: List<LogEntry> = emptyList()): List<Food> {
        val used = logs.filter { !it.deleted && it.refType == RefType.FOOD }.groupingBy { it.refId }.eachCount()
        return library.foods.values.filter { !it.deleted && same(draft, it) }
            .sortedWith(compareByDescending<Food> { !draft.barcode.isNullOrBlank() && it.barcode == draft.barcode }.thenByDescending { used[it.id] ?: 0 })
    }

    /**
     * How many of the survivor's units one unit of [from] is ("pc" of the wrap = 40 g of the label food):
     * same unit = 1, else by calories (protein if no calories), else g and ml count as equal.
     */
    fun factor(from: Food, to: Food): Double? {
        if (from.unit == to.unit) return 1.0
        fun per(f: Food, v: Double?) = v?.takeIf { it > 0 && f.baseQty > 0 }?.div(f.baseQty)
        per(from, from.kcal)?.let { a -> per(to, to.kcal)?.let { b -> return a / b } }
        per(from, from.protein)?.let { a -> per(to, to.protein)?.let { b -> return a / b } }
        return if (from.unit in WEIGHT_UNITS && to.unit in WEIGHT_UNITS) 1.0 else null
    }

    /**
     * The merged food: [keep]'s id and name, [valuesFrom]'s unit and numbers, any brand or barcode the
     * others had, their names as aliases; plus the units everything was counted in, converted.
     */
    data class Merge(val food: Food, val portions: List<FoodPortion>, val gone: List<Food>, val factors: Map<String, Double?>)

    fun plan(library: Library, group: Group, keep: Food = group.keep.food, valuesFrom: Food = group.valuesFrom.food, now: Long = System.currentTimeMillis()): Merge {
        val all = group.members.map { it.food }
        val others = all.filter { it.id != keep.id }
        val aliases = (keep.aliases.split(",") + others.map { it.name } + others.flatMap { it.aliases.split(",") })
            .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(keep.name, ignoreCase = true) }.distinctBy { it.lowercase() }
        val food = keep.copy(
            unit = valuesFrom.unit, baseQty = valuesFrom.baseQty,
            kcal = valuesFrom.kcal, protein = valuesFrom.protein, fibre = valuesFrom.fibre ?: all.firstNotNullOfOrNull { f -> f.fibre?.takeIf { f.unit == valuesFrom.unit } },
            carbs = valuesFrom.carbs, fat = valuesFrom.fat,
            brand = valuesFrom.brand?.takeIf { it.isNotBlank() } ?: all.firstNotNullOfOrNull { it.brand?.takeIf { b -> b.isNotBlank() } },
            barcode = valuesFrom.barcode?.takeIf { it.isNotBlank() } ?: all.firstNotNullOfOrNull { it.barcode?.takeIf { b -> b.isNotBlank() } },
            source = valuesFrom.source, kind = if (all.any { it.kind == FoodKind.PRODUCT }) FoodKind.PRODUCT else keep.kind,
            aliases = aliases.joinToString(", "), archived = false, deleted = false, updatedAt = now,
        )
        val factors = all.associate { it.id to factor(it, valuesFrom) }
        // Units: the values food's own first, then each member's, converted; plus "pc" for a member counted in pieces.
        val portions = ArrayList<FoodPortion>()
        fun add(label: String, qty: Double) {
            if (label == food.unit || qty <= 0 || portions.any { it.label == label }) return
            portions += FoodPortion(java.util.UUID.randomUUID().toString(), keep.id, label, Math.round(qty * 10) / 10.0, now)
        }
        (listOf(valuesFrom) + all.filter { it.id != valuesFrom.id }).forEach { f ->
            val k = factors[f.id] ?: return@forEach
            library.portions[f.id].orEmpty().forEach { p -> add(p.label, p.qty * k) }
            if (f.unit != food.unit) add(f.unit, k)
        }
        return Merge(food, portions, others, factors)
    }
}
