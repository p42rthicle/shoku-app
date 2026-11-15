package app.shoku.data.ai

import app.shoku.data.net.BackendException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The prompts, JSON schemas and request shapes of backend/functions/api/ai.js, ported line by line for
 * direct mode. Keep the two in step: AiTransportTest checks that the prompt texts here appear verbatim
 * in ai.js. Nothing here talks to the network.
 */

/** Photos need the strongest vision for portion sizes; text tasks are fine on Sonnet at half the price. */
const val PHOTO_MODEL = "claude-opus-5-5"
const val TEXT_MODEL = "claude-sonnet-5-5"

data class SystemBlock(val text: String, val cache: Boolean = false)

sealed interface UserBlock {
    data class Text(val text: String) : UserBlock
    data class Image(val base64: String, val mediaType: String) : UserBlock
}

/**
 * One structured call: effort low, JSON-schema output. [fallbacks] adds the server-side refusal fallback
 * (beta `server-side-fallback-2026-07-01`, `fallbacks: "default"`), as ai.js does for every route but the
 * units batch.
 */
data class AskRequest(
    val model: String,
    val maxTokens: Long,
    val system: List<SystemBlock>,
    val content: List<UserBlock>,
    val schema: JsonObject,
    val fallbacks: Boolean = true,
)

// ---------- Schemas ----------

private fun typed(type: String, description: String? = null, enum: List<String>? = null) = buildJsonObject {
    put("type", type)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    if (description != null) put("description", description)
}

private val num = typed("number")
private fun nullable(schema: JsonObject) = buildJsonObject {
    putJsonArray("anyOf") { add(schema); add(typed("null")) }
}
private fun arr(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }

/** Every object in ai.js is closed and requires all of its properties. */
private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { props.forEach { add(it.first) } }
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
}

private val MEALS = listOf("Breakfast", "Lunch", "Snacks", "Dinner")

private val ESTIMATE = obj(
    "kcal_per_100g" to num, "protein_per_100g" to num, "fibre_per_100g" to num, "carbs_per_100g" to num, "fat_per_100g" to num,
    "grams" to typed("number", "Estimated grams eaten for this item"),
)

private val ITEM = obj(
    "said" to typed("string", "The words this item came from"),
    "match_key" to nullable(typed("string", "Key of the library item, e.g. \"f12\"")),
    "name" to typed("string", "Library name when matched, otherwise a short clear English name"),
    "brand" to nullable(typed("string", "Brand when they name one or it shows on the pack, never part of name")),
    "unit" to typed("string", "One of the matched item's units, or \"g\" when not matched"),
    "qty" to num,
    "confidence" to typed("string", enum = listOf("high", "medium", "low")),
    "estimate" to nullable(ESTIMATE),
)

val MEAL_RESULT = obj("items" to arr(ITEM), "meal" to nullable(typed("string", enum = MEALS)))

val LABEL_RESULT = obj(
    "readable" to typed("boolean", "False if no nutrition table can be read"),
    "name" to nullable(typed("string")),
    "brand" to nullable(typed("string")),
    "unit" to typed("string", "Basis of the per-100 column", listOf("g", "ml")),
    "kcal" to nullable(num), "protein" to nullable(num), "fibre" to nullable(num), "carbs" to nullable(num), "fat" to nullable(num),
    "serving_size" to nullable(typed("number", "Grams or ml in one serving, if printed")),
    "note" to nullable(typed("string", "Anything uncertain, briefly")),
)

val RECIPE_RESULT = obj("name" to nullable(typed("string")), "servings" to nullable(num), "ingredients" to arr(ITEM))

val UNITS_RESULT = obj(
    "foods" to arr(
        obj(
            "k" to typed("string"),
            "grams_per_unit" to nullable(typed("number", "Only for foods counted in pieces, servings, cans or packs: grams (ml for drinks) in one. Otherwise null.")),
            "portions" to arr(
                obj(
                    "label" to typed("string", "Short lowercase household unit, e.g. tbsp, tsp, cup, glass, slice, katori, bowl, scoop, bar, piece, small, medium, large"),
                    "amount" to typed("number", "How much of the food's own unit (g, ml, pc, ...) one of this portion is"),
                ),
            ),
        ),
    ),
)

val PLAN_RESULT = obj(
    "templates" to arr(
        obj(
            "name" to typed("string"),
            "meal" to typed("string", enum = MEALS),
            "weekdays" to arr(typed("integer", "1 = Monday ... 7 = Sunday")),
            "items" to arr(
                obj(
                    "match_key" to typed("string", "Library key, or \"slot\" for the sabzi/dal batch in the fridge"),
                    "unit" to typed("string"),
                    "qty" to num,
                ),
            ),
        ),
    ),
    "notes" to typed("string", "Two or three short sentences: what changed and why"),
)

// ---------- Prompts (verbatim from ai.js) ----------

const val RULES = """You help one person log what they eat. They live in Germany, buy from Aldi/Lidl/Rewe, and cook a lot of North Indian home food.

Matching to their library:
- Each library line is: key | name | units | also known as. Match an item only when it is really the same food. A generic ("any brand") item is the right match when they don't name a brand or name a brand that isn't in the library.
- The unit must be one of that item's listed units. Prefer a named portion (medium, slice, tin, tbsp, bowl) when they describe one; use g or ml when they give a weight or volume.
- If nothing in the library fits, set match_key to null, use unit "g", and fill estimate with typical values per 100 g plus the grams eaten. Think of realistic home portions (a katori of dal is about 150 g, a roti about 40 g, a cup of cooked rice about 160 g).
- Never add items they didn't mention. Split combined descriptions into separate items ("2 eggs and toast with butter" is three items).
- A brand they name, or one readable on a pack in a photo, goes in brand and never in name: "Yadav chakli" is name "Chakli", brand "Yadav". Otherwise brand is null.
- confidence: high when the item and amount are clear, medium when one is guessed, low when both are."""

const val MEAL_TASK_TEXT = "Task: turn their description of what they ate into items. If the description says which meal it was, set meal; otherwise null."

const val LABEL_TASK = "Task: read the nutrition table on this food package. Use the per 100 g / 100 ml column (not per serving). Energy in kcal (convert from kJ only if kcal is missing). Name and brand from the package if visible. German labels: Brennwert = energy, Eiweiß = protein, Ballaststoffe = fibre, Kohlenhydrate = carbs, Fett = fat. If the table is not readable, set readable false."

const val PLATE_TASK = "Task: estimate what is on this plate or in this bowl. List each visible food with a realistic amount; use the plate, cutlery, glasses and bowls for scale. Match to their library when it is clearly the same dish (e.g. their own dal or rice batch). Confidence is at most medium for anything judged by eye."

const val RECIPE_TASK = "Task: they describe what went into a pot for one cook (a batch). Return every ingredient with its amount for the whole pot, matched to their library where possible. Spices under 5 g can be skipped unless named with an amount. If they say how many servings or the dish name, fill those."

const val UNITS_TASK = """Task: for each food, list the household units people actually use for it, with how much of the food's own unit one of them is. Use realistic values for that kind of food (a tbsp of Nutella is about 19 g, a tbsp of oil about 13 g, a slice of toast about 25-30 g, a glass of milk about 250 ml, a katori of dal about 150 g, a scoop of whey about 30 g).
- 1 to 4 portions per food, only ones that make sense (no "cup" for chocolate bars, no "slice" for milk). Never repeat the food's own unit.
- For foods measured in pieces, servings, cans or packs, set grams_per_unit to the weight of one (ml for drinks) and usually give no portions. Otherwise grams_per_unit is null.
- Keep labels short, lowercase, English.
Each line is: key | name | own unit | brand."""

const val PLAN_TASK = """Task: plan their week as meal templates, built only from their library (match_key from the library; "slot" means one serving of whatever sabzi or dal batch they cooked).
Principles, in order: 1) every meal is built around a protein source from their usual ones; 2) colour and fibre: vegetables, curd, nuts and seeds; 3) supplements cover the rest, so don't plan foods to replace them. Protein powder (whey) is a food in their plan, not a supplement: keep it.
Hit the daily calorie and protein goals within about 5%. Keep it repetitive (same breakfast and lunch most days); they don't want variety for its own sake. Prefer what is in stock and foods that would otherwise run out unused. Respect the rules they give (for example days without eggs or chicken). Units must be one of the item's listed units. Every weekday 1-7 must have one template per meal they eat."""

fun mealTask(meal: String) = """Task: suggest exactly 3 different options for their $meal, as templates built only from their library (match_key from the library; "slot" means one serving of whatever sabzi or dal batch they cooked). Leave weekdays empty.
Principles, in order: 1) built around a protein source they actually eat; 2) colour and fibre: vegetables, curd, nuts and seeds; 3) supplements cover the rest. Protein powder (whey) is a food, not a supplement.
Use what they eat often (their history) and what is at home, especially things that would run out unused. Keep each option realistic for them, close to their usual portions, and sized for this meal's share of the day (roughly a quarter to a third of the calorie goal for lunch or dinner, less for breakfast or snacks). Make the 3 options genuinely different from each other and from their current $meal templates. Units must be one of the item's listed units. Name each in a few plain words ("Paneer bhurji, roti, salad")."""

// ---------- Routes: request body (the backend's JSON) -> AskRequest ----------

/** Turns the app's request bodies into Claude requests exactly as ai.js does. Errors carry ai.js's messages. */
object DirectRoutes {
    const val UNITS_CHUNK = 40
    val ASK_ROUTES = setOf("/ai/meal", "/ai/label", "/ai/plate", "/ai/recipe", "/ai/plan")

    fun build(path: String, body: JsonObject): AskRequest = when (path) {
        "/ai/meal" -> parseMeal(body)
        "/ai/label" -> readLabel(body)
        "/ai/plate" -> plate(body)
        "/ai/recipe" -> recipe(body)
        "/ai/plan" -> planWeek(body)
        else -> throw BackendException(404, "no route for POST $path")
    }

    fun libraryText(lib: JsonElement?): String {
        val list = (lib as? JsonArray).orEmpty()
        if (list.isEmpty()) return "The library is empty."
        return list.take(600).joinToString("\n") { f ->
            val o = f as? JsonObject ?: JsonObject(emptyMap())
            val units = (o["units"] as? JsonArray).orEmpty().joinToString(", ") { jsString(it) }
            listOf(jsString(o["k"]), jsString(o["name"]), units, o.str("aka").orEmpty()).joinToString(" | ")
        }
    }

    /** Stable parts first (rules, then the library) so every route shares one cached prefix; the task goes in the user turn. */
    private fun ask(task: String, library: JsonElement?, content: List<UserBlock>, schema: JsonObject, maxTokens: Long = 4000, model: String = TEXT_MODEL): AskRequest {
        val system = mutableListOf(SystemBlock(RULES))
        if (library != null && library !is kotlinx.serialization.json.JsonNull) system += SystemBlock("Their food library:\n${libraryText(library)}", cache = true)
        return AskRequest(model, maxTokens, system, listOf(UserBlock.Text(task)) + content, schema)
    }

    private fun imageBlock(data: JsonElement?, mediaType: String?): UserBlock.Image {
        val d = (data as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (d.isNullOrEmpty()) throw BackendException(400, "image (base64) is required")
        if (d.length > 6_000_000) throw BackendException(413, "image too large; send a JPEG of about 1600 px")
        val type = mediaType?.takeIf { it in listOf("image/jpeg", "image/png", "image/webp") } ?: "image/jpeg"
        return UserBlock.Image(d, type)
    }

    private fun images(body: JsonObject): List<UserBlock.Image> {
        val many = body["images"] as? JsonArray
        val list = if (many != null && many.isNotEmpty()) many.toList() else listOf(body["image"])
        if (list.size > 4) throw BackendException(400, "at most 4 photos")
        return list.map { imageBlock(it, body.str("mediaType")) }
    }

    private fun requiredText(body: JsonObject): String = body.str("text")?.takeIf { it.isNotEmpty() } ?: throw BackendException(400, "text is required")

    private fun parseMeal(body: JsonObject) = ask(
        MEAL_TASK_TEXT, body["library"], listOf(UserBlock.Text(requiredText(body).take(2000))), MEAL_RESULT,
    )

    private fun readLabel(body: JsonObject) = ask(
        LABEL_TASK, null, listOf(imageBlock(body["image"], body.str("mediaType")), UserBlock.Text("Read this label.")), LABEL_RESULT, maxTokens = 2000,
    )

    private fun plate(body: JsonObject): AskRequest {
        val photos = images(body)
        val text = body.str("text")
        val extra = if (text != null && text.isNotBlank()) "\nThey add: ${text.take(500)}" else ""
        val angles = if (photos.size > 1) {
            " There are ${photos.size} photos of the same meal from different angles: use them together to judge depth and portion size, and count each food once."
        } else ""
        return ask(PLATE_TASK + angles + extra, body["library"], photos + UserBlock.Text("What is here and how much?"), MEAL_RESULT, model = PHOTO_MODEL)
    }

    private fun recipe(body: JsonObject) = ask(
        RECIPE_TASK, body["library"], listOf(UserBlock.Text(requiredText(body).take(3000))), RECIPE_RESULT,
    )

    private fun planWeek(body: JsonObject): AskRequest {
        val g = body["goals"] as? JsonObject ?: JsonObject(emptyMap())
        val meal = body.str("meal")?.takeIf { it in MEALS }
        val history = body.strings("history").take(60).joinToString("; ")
        val text = body.str("text")
        val lines = listOf(
            "Goals per day: ${jsNumber(g.num("kcal"), 1800.0)} kcal, ${jsNumber(g.num("protein"), 110.0)} g protein.",
            "Rules: ${body.strings("rules").joinToString("; ").ifEmpty { "none" }}.",
            "At home now: ${body.strings("stock").take(200).joinToString("; ").ifEmpty { "unknown" }}.",
            "Current plan: ${body.strings("current").take(40).joinToString(" | ").ifEmpty { "none" }}.",
            if (history.isNotEmpty()) "What they eat most (last months): $history." else "",
            if (text != null && text.isNotBlank()) "They ask: ${text.take(1000)}" else "",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return ask(if (meal != null) mealTask(meal) else PLAN_TASK, body["library"], listOf(UserBlock.Text(lines)), PLAN_RESULT, maxTokens = 12000)
    }

    /** The units batch: one request per 40 foods, keyed u0, u1, ... */
    fun unitsRequests(body: JsonObject): List<Pair<String, AskRequest>> {
        val foods = (body["foods"] as? JsonArray).orEmpty().take(2000)
        if (foods.isEmpty()) throw BackendException(400, "foods is required")
        return foods.chunked(UNITS_CHUNK).mapIndexed { i, chunk ->
            val part = chunk.joinToString("\n") { f ->
                val o = f as? JsonObject ?: JsonObject(emptyMap())
                listOf(jsString(o["k"]), jsString(o["name"]), jsString(o["unit"]), o.str("brand").orEmpty()).joinToString(" | ")
            }
            "u$i" to AskRequest(TEXT_MODEL, 8000, listOf(SystemBlock(UNITS_TASK)), listOf(UserBlock.Text(part)), UNITS_RESULT, fallbacks = false)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().map { jsString(it) }

    /** What JavaScript prints for a value in a template string or join: no quotes, "" for null. */
    private fun jsString(e: JsonElement?): String = when (e) {
        null, is kotlinx.serialization.json.JsonNull -> ""
        is JsonPrimitive -> if (e.isString) e.content else e.doubleOrNull?.let { jsNumber(it, it) } ?: e.content
        else -> e.toString()
    }
}

/** `Number(x) || fallback`, printed the way JavaScript prints numbers (1800, not 1800.0). */
internal fun jsNumber(value: Double?, fallback: Double): String {
    val v = value?.takeIf { it != 0.0 && !it.isNaN() } ?: fallback
    return if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
