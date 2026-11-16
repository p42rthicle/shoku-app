package app.shoku.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shoku.ui.theme.ShokuColors

/** One release: version (matching versionName in build.gradle.kts), date and what changed. Newest first. */
data class Release(val version: String, val date: String, val changes: List<String>)

/**
 * What's new, shown when the version in Settings is tapped. Add an entry with every version bump
 * (versionName and versionCode in app/build.gradle.kts).
 */
val CHANGELOG = listOf(
    Release(
        "0.9.4", "16 Nov 2026",
        listOf(
            "Meal photos: a plate you log with a photo keeps a smaller copy of the photo (\"Save photo with the meal\", on by default); a camera icon on those entries opens it, and you can swipe through all your meal photos (also from Insights › Meal photos)",
            "A camera button on each meal on Today adds a photo of it without any estimate (no AI): it's saved with the meal's entries and in your meal photos",
            "Shoku AI learns from your corrections: change what it estimated (another food or amount, in the review or later on Today) and the next describe or photo follows it, along with your usual portions",
        ),
    ),
    Release(
        "0.9.33", "16 Nov 2026",
        listOf(
            "Scanning a pack to log it offers \"Bought it? Add to stock instead\"",
            "Fixed: Scan crashed (every scan button) since the faster build; checked every screen for crashes",
            "Deleting an entry leaves Undo up longer; eaten entries are deleted from their sheet, so a sideways swipe over them changes the day again",
            "Adding a scanned pack to stock starts at the whole pack (a 1 kg bag of rice: 1000 g), or at what you added last time, and adds to the stock you already have",
        ),
    ),
    Release(
        "0.9.32", "16 Nov 2026",
        listOf(
            "Today's + button can add to stock too: \"+ Add to stock instead\" at the top of Add opens the stock sheet (search, scan, label, pack) right there",
            "A short confirmation when stock is added, updated or removed, something moves to Supplements, or foods are merged",
        ),
    ),
    Release(
        "0.9.31", "16 Nov 2026",
        listOf(
            "Archived foods (end of Library › Foods) lists only foods you archived, not the old \"Apple (m)\" size rows kept for past entries",
            "Search Everything finds archived foods too, marked \"Archived\", to open, bring back or delete; logging never offers them",
            "Deleting a logged item: the entry's sheet has a labelled Delete button, and any entry (eaten too) can be swiped away, with Undo",
            "The entry's brand field is called \"Brand\" (the note has its own field)",
        ),
    ),
    Release(
        "0.9.3", "16 Nov 2026",
        listOf(
            "Back from anything you opened in Search (a food, a stock item, a dish) returns to your search, with the same words and scroll",
            "A stock item's \"Counted in\" converts the amount: 1520 g shows as 38 pc",
            "Tap a stock item's name (or the pencil) to edit the food; closing it returns to the stock item",
            "Leaving a food with unsaved changes asks \"Save changes?\" instead of losing them",
            "A food that isn't used anywhere can be deleted, not just archived",
            "Merging no longer makes two units for the same amount (a 40 g \"wrap\" next to a 40 g \"pc\")",
            "Pack photo: for packs with no barcode match or no label (a creatine tub), take a photo of any side and Shoku AI fills in the brand, the name and typical values (or the label's, if it shows one). In Add, every search sheet and Library",
            "Settings › Find duplicate foods: foods that are in your library twice, with the one to keep (logged most, or most complete) and why; merging keeps past entries as they were, pointing to the one food, and moves dishes, plans and stock with it",
            "Merging picks the cleanest name (brand kept in its own field), fills every missing value from the other copies, keeps barcodes and units, and shows the result before you merge; stock stays as it was",
            "Smooth 120 Hz scrolling: the app now runs as the optimised build, and long meals on Today are built a row at a time",
            "Names marked [o] for food ordered online are now plain (Chicken Curry), with \"Ordered online\" and the place in the entry's note",
            "A new food that's already in your library asks \"Already in your library?\"; using it adds the scanned barcode, so the next scan finds it",
            "Picking a database or online product you log under your own name asks \"Did you mean yours?\"",
            "Pack photos: add the front and the back of a pack (up to 4); the name and brand come from the front, the values from the label",
            "Adding to stock asks whether it's a food or a supplement (creatine, vitamins and the like start on Supplement) and where it goes (fridge, freezer, pantry, spices)",
            "Something already in stock as a food can move to Supplements from its stock card, keeping the amount you have",
            "A label photo with no nutrition table works out what the product is instead of failing",
        ),
    ),
    Release(
        "0.9.2", "16 Nov 2026",
        listOf(
            "Today's plan uses what you cooked: your rice batch stands in for the template's rice, and the sabzi slot takes a curry batch (never rice); each meal on the card lists what it will actually add, fridge batches marked",
            "Fixed a freeze when logging with AI: the loader shows at once, the lookups run in the background, and a second tap is ignored",
            "Searching is much faster everywhere",
            "\"Usual for…\" and search favour what you've eaten lately, not habits from months ago",
            "Supplements: tell it how many you had and on which day, and it takes off every dose you've ticked since, then says how long the stock lasts and when to order (a week ahead)",
            "Today shows a \"Running low\" card when a supplement runs out within two weeks",
            "Today's plan folds away with the arrow by its title; folded, it's one line with an \"Add as per plan\" chip (it stays folded until you open it)",
        ),
    ),
    Release(
        "0.9.1", "16 Nov 2026",
        listOf(
            "Indian packaged foods scan offline: 15,000 products from Open Food Facts are built into the app",
            "Use your own Claude API key (Settings › AI): calls go straight from the phone to Anthropic, with the same monthly cap",
            "Wrong entries from Open Food Facts (calories that don't match the macros) are skipped",
            "Scanning a pack or reading a label no longer adds a unit by itself: the pack's serving is offered as a suggestion you can tap",
            "Typing in search is smooth: results update a moment after you pause, worked out in the background",
            "Search finds the built-in Indian products too (milk cake: Haldiram's, Amul, Bikano…); online results need every word in the name",
            "New foods start in grams; a database serving like \"1 slice = 287 g\" is there to pick, not the default",
            "A food you archived no longer hides it from the food databases; picking it brings it back",
            "Anything the AI estimates (plate photo, describe) is saved to your library when logged, so next time it's a normal match; past estimates were converted too",
            "AI keeps brands apart: \"Yadav chakli\" becomes Chakli with the brand Yadav; tap an item in the AI review to set or fix its brand",
            "Smaller download for shared builds (about 5 MB)",
        ),
    ),
    Release(
        "0.9.0", "15 Nov 2026",
        listOf(
            "Global search (Today, top right): what's at home, your log (including notes), foods, dishes, batches, plan, supplements, databases and online products",
            "Notes on logged food, like the restaurant you had it at",
            "Stock moved to Library, with its own search; fix a wrongly entered unit; best-before date picker",
            "Scan, label photo and new food wherever you add food; cook a new dish straight from Add",
            "Packs that Open Food Facts lists without nutrition go to the label photo",
            "Shorter lists everywhere with See all; finished batches and archived foods fold away",
            "Brand on any food; Edit food, Open dish and Open batch from a logged entry",
            "Supplements: add, rename, remove; All taken in one tap",
            "Food rules for AI (Plan › Meals); fibre goal; weight for any day",
            "Safer edits: Undo, Save changes?, two-tap delete, AI answers can't be swiped away",
            "Spring animations: buttons, cards, tabs, bars and the calorie count",
        ),
    ),
    Release(
        "0.8.0", "14 Nov 2026",
        listOf(
            "Plan tab: supplements with reminders, meal templates by weekday, home stock and a shopping list",
            "Meal menu with AI ideas, Plan my week with AI and the Sunday shopping list",
            "Home-screen and lock-screen widget",
            "Units for every food, smarter search with amounts, Open Food Facts search",
            "Dark mode, weight tracking, Health Connect export",
        ),
    ),
    Release(
        "0.5.0", "13 Nov 2026",
        listOf(
            "AI logging: describe a meal, read a label, estimate a plate photo, recipe to batch",
            "Daily log, library of foods, dishes and batches, sync and backups",
        ),
    ),
)

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShokuColors.Paper,
        title = { Text("What's new") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CHANGELOG.forEach { r ->
                    Text("${r.version} · ${r.date}", style = MaterialTheme.typography.titleMedium)
                    r.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = ShokuColors.Ink) } },
    )
}
