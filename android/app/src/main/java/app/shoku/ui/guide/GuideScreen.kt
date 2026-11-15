package app.shoku.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing

private data class Section(val title: String, val points: List<String>)

private val SECTIONS = listOf(
    Section(
        "Today",
        listOf(
            "The big number is calories left. The bars show eaten (dark) and planned (light). Red means over your calorie goal.",
            "Swipe left or right on the screen to go to the next or previous day. Tap the date to jump anywhere; the arrows work too.",
            "Back goes to the tab you came from, and from another day back to today; only Today closes the app. Long-press the app icon for Describe, Scan and Stock.",
            "“Carbs and fat” opens with the arrow under the bars.",
            "“In the fridge” lists batches you cooked this week and how much is left. After 3 days it asks if it's still there; a batch that's used up finishes itself.",
            "“Your usual breakfast” (or lunch, …) appears while that meal is empty: Log all adds everything you normally have in one tap.",
        ),
    ),
    Section(
        "Logging food",
        listOf(
            "Tap + (or “Add to breakfast” in a meal card). The meal is picked from the time of day (start times in Settings → Meals); change it at the top.",
            "Only eat three meals? Turn Snacks off in Settings → Meals, from today or for chosen dates. Days where you logged snacks keep showing them.",
            "“Usual for …” shows what you normally eat at that meal with your normal amount. The black + logs it in one tap.",
            "“Copy … from …” repeats a whole meal from the last day you had it.",
            "Search finds your foods first, then Indian and German databases, then online products (Open Food Facts). English, German and Hindi names work; umlauts, plurals and small typos don't matter.",
            "Type the amount too: “nutella 4 tbsp”, “200g skyr”, “2 eggs”. The + then logs exactly that.",
            "The top row “Log … with AI” sends what you typed to the AI (about 1¢), which matches your own foods first. Use it for several things at once: “2 toast, an egg and coffee”.",
            "Tap an item to set the amount: type it, use the chips, or switch units (tbsp, slice, medium, g).",
        ),
    ),
    Section(
        "Planned and eaten",
        listOf(
            "Log ahead as Planned, then tap the circle when you've eaten it.",
            "Swipe an uneaten entry left or right to delete it (with Undo). Eaten entries are deleted from their edit sheet, so they can't be swiped away by accident.",
            "Tap any entry to change the amount, meal, day or status.",
            "Wrong item (say an AI guess at a restaurant)? Tap it → Replace with another food → search your foods, the databases or online products. The amount carries over (same unit, or the same grams) and you confirm it.",
            "Past days: go back with the arrows, a swipe, or by tapping the date, then add as usual. Entries on past days are logged as eaten.",
            "~ before calories means the numbers are typical (any brand) or estimated rather than exact.",
        ),
    ),
    Section(
        "Any brand, portions",
        listOf(
            "Generic foods (“Any brand”) like Whole milk, Toast, Banana or Kidney beans use typical values, so a new brand doesn't need a new food. Add the brand as a note if you like.",
            "Your branded foods stay as they are and sit under their generic in the Library.",
            "Every food can have several units (tbsp, slice, glass, g). Products share their family's units, the AI fills in missing ones once in a while (as a cheap batch), and you can add your own in the food editor under Units.",
        ),
    ),
    Section(
        "Scanning and photos",
        listOf(
            "Scan (top right of Add): a barcode you've scanned before logs straight away. A new one is looked up on Open Food Facts; check the numbers and save.",
            "Label: photograph the nutrition table and the AI reads it into a new food. If a barcode isn't known, “Read the label with a photo” keeps the barcode.",
            "Plate: add up to 4 photos of the same food from the camera or gallery. A top view and a side view with a fork or glass in shot give the best portion estimate. Add a note like “I ate half”.",
            "Describe: type or dictate what you ate (“2 rotis, a katori of rajma, 150 g skyr”). Amounts make it more accurate.",
            "AI results always open a review first: untick wrong items, tap to fix amounts, then Log as eaten, or Save as draft to fix later from Today.",
        ),
    ),
    Section(
        "Library, dishes and batches",
        listOf(
            "Foods: tap to edit. “Update past entries” recalculates old entries and batches that used it.",
            "Dishes are recipes. Batches are one real cook of a dish: tap “Cook a batch”, adjust what went in, set servings and the cooked weight (weigh the pot; you can add it later).",
            "“Describe what went in (AI)” fills the ingredient list from a sentence. New ingredients are added as foods from estimates; check them in Library.",
            "Log from a batch by servings or grams. When it's gone, mark it Finished.",
        ),
    ),
    Section(
        "Plan: meals, supplements, stock",
        listOf(
            "Meals: your plan as meal templates per weekday (Tuesdays without eggs or chicken). Each card shows its calories and protein; tap to change items, amounts or days. The batch slot uses whatever sabzi or dal batch is in the fridge.",
            "Today shows “Today's plan” while meals are empty: tap Change on a meal to use another of your templates (or leave it out) for that day, then Add as planned; tick items as you eat them, Replace anything that changed.",
            "Plan → Meals → Meal menu: every option per meal. Tap the day chips under one to use it on those days, so meals mix and match across the week. “From your history” turns meals you've eaten often into templates; “AI ideas” suggests 3 new ones from your stock, habits and goals (about 2–3¢).",
            "Cooked something? “+ Cook” on Today (or + in Library → Batches) asks which dish, then what went in, servings and the cooked weight.",
            "Prep reminders on Sunday and Wednesday at 18:00 say how much chicken to marinate, paneer to make (and milk for it) and batch servings to cook until the next prep day.",
            "Supplements: when and how much, stock and the day each runs out. Due supplements show as chips in Today's meal cards (tap = taken) and on the meal reminder with a Taken button. Not medical advice.",
            "Stock: scan packs, search (your foods, databases, online) or say what you have. Eating, cooking batches and taking supplements use it up by themselves; tap an item to recount, set where it lives, a best-before date or a buy-below amount. Spices are just have / low / out.",
            "To buy: anything running out within about 4 days, under its buy-below amount, marked low or out, or in your plan with none at home. Bought adds it back to stock.",
        ),
    ),
    Section(
        "Insights, goals, sync",
        listOf(
            "Insights shows each month: averages, days logged, protein met, calories on target, daily bars and heatmaps. Tap a day to open it.",
            "Settings: change your goal (applies from today; past days keep theirs), export a backup ZIP, and Sync now.",
            "Reminders (Settings): a nudge per meal only when that meal is still empty, with Log usual and Describe buttons on the notification. Optional Sunday summary.",
            "AI this month (Settings): what AI has cost and the monthly limit ($30 by default). AI stops at the limit.",
            "Weight (Insights): log a weigh-in; the line is a 7-day average so daily swings don't matter.",
            "Appearance: System, Light or Dark. Health Connect: optional export of eaten food and weight.",
            "Sync runs by itself when you open or leave the app, so a new phone gets everything back.",
        ),
    ),
)

@Composable
fun GuideScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.height(14.dp))
            ScreenTitle("How to use Shoku", eyebrow = "Guide")
            Spacer(Modifier.height(4.dp))
        }
        items(SECTIONS) { section ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(section.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.points.forEach { point ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                            Spacer(Modifier.width(8.dp))
                            Text(point, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Ink)
                        }
                    }
                }
            }
        }
    }
}
