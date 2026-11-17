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
        "0.9.63", "17 Nov 2026",
        listOf(
            "Stock search (and At home in Search everything) finds a food by its Also known as names and plurals: \"Protein Wraps\" finds your High-protein wrap",
            "Your foods in Search everything show what's at home first: \"1520 g in stock · Pantry · per 100 g · 318 kcal\"",
        ),
    ),
    Release(
        "0.9.62", "17 Nov 2026",
        listOf(
            "Search everything finds what's at home by brand too (SDS), and \"pantry\", \"fridge\", \"freezer\" or \"spices\" lists what's there; \"stock\" lists everything",
            "Stock results show what's left as on the Stock tab: \"Spices · ≈ 60 g left · about 2 pack\"",
        ),
    ),
    Release(
        "0.9.61", "17 Nov 2026",
        listOf(
            "Nothing a scan finds is thrown away: the pack size (from the barcode, the label or a pack photo) is saved as a \"pack\" unit, and the pack's serving as a unit, already filled in when you save the food",
            "Adding a packed food to stock starts at 1 pack, so 2 sachets of 30 g is just \"2 pack\"",
            "Use this (an existing food) keeps the scan's pack, serving and name (as Also known as)",
            "Barcode and label disagree on the name: the one you don't pick is kept as Also known as",
            "Add anyway on a bill: cancelling goes back to the review and keeps the line; adding it goes back too",
        ),
    ),
    Release(
        "0.9.60", "17 Nov 2026",
        listOf(
            "Bill review always shows your latest bill too, so a bill where everything matched still shows what it added and its \"not food\" lines (Add anyway)",
            "The same bill is still spotted after you removed or added lines in the review",
            "Undoing a bill right after adding it removes the foods it made reliably",
        ),
    ),
    Release(
        "0.9.59", "17 Nov 2026",
        listOf(
            "Fixed: Review on the bill card on Stock did nothing (broken in 0.9.57)",
            "Spices show how much is left (\"≈ 40 g left\"), like the fridge and pantry, and go down as you cook and eat with them",
            "A new spice keeps the amount you add, and runs low at a fifth of it (100 g: low at 20 g, then it's on To buy)",
            "Spices you had as have / low / out ask for their amount once: \"Amount not set · tap to add\"",
            "Spices without spoon sizes get them (1 tsp ≈ 2.5 g, 1 tbsp ≈ 7.5 g), so \"2 tsp\" in a recipe comes off the pack",
            "Have / low / out moved off the list into the item, for things you'd rather not count",
        ),
    ),
    Release(
        "0.9.58", "17 Nov 2026",
        listOf(
            "Scanning a product named after a brand finds your food of that brand: Nutella (Ferrero) offers your \"Hazelnut chocolate spread\" [Nutella] when the values match, and Use this saves the barcode on it",
        ),
    ),
    Release(
        "0.9.57", "17 Nov 2026",
        listOf(
            "Sharing a receipt from the Lidl app opens the Shoku you already have, on Stock, instead of a second copy",
            "The same bill shared twice is spotted: \"Already added?\" with OK or Add it again, so stock isn't doubled",
            "Stock › Bill › Recent bills: undo a whole bill, everything it added taken back out",
        ),
    ),
    Release(
        "0.9.56", "17 Nov 2026",
        listOf(
            "Fixed: a bill's red bell pepper went into Spices as have/low/out (it read \"pepper\"), so Edit showed no amount; fresh vegetables and herbs now go to the fridge, counted, and the one from your bill is moved",
            "A stock card can count in a new unit: + unit, \"pc = 150 g\", then switch to it",
            "New things from a bill come with a piece unit when they're bought in pieces (one red pepper about 150 g)",
            "Stock cards have a clear \"Edit the food\" button; editing from the bill review returns to it, that item checked",
            "Bill review: Replace returns to the review and starts at the bill's amount; removing a replaced item takes off only its amount",
            "Bill review shows the matched items of every bill still being checked, not just the latest",
            "Add anyway opens Find & add with the line's name filled in",
            "A photo with no readable bill now says so, instead of \"0 items added\"",
        ),
    ),
    Release(
        "0.9.55", "17 Nov 2026",
        listOf(
            "Bills to stock: share a receipt from the Lidl app to Shoku (\"Add bill to stock\"), or Stock › Bill for screenshots or a photo of a paper bill",
            "What's clearly your food goes onto its stock straight away; new things are looked up (Open Food Facts for packs, the food database for fruit and vegetables, an estimate last), added too, and wait for you to check",
            "A notification when the bill is in, and a card on Stock: keep, edit, replace (search, scan or a photo) or remove each new item; untick several to remove them at once",
            "Toiletries, clothes and other non-food lines are listed as not added, with Add anyway",
            "Say it's new items show up for checking the same way",
        ),
    ),
    Release(
        "0.9.49", "17 Nov 2026",
        listOf(
            "Scanned a pack, then read its label (or a photo of the pack)? The two are merged instead of the label replacing everything: name and brand stay from the barcode, the label fills in what was missing, and where they disagree you pick: \"Barcode or label?\" with both values side by side",
            "After a scan you can always add values from the label, even when the barcode found the product",
            "Copy an earlier meal, Add as per plan and Remove planned open a list with round ticks first: untick what you don't want",
            "The AI review says it too: untick anything you don't want",
            "Searching stock by brand (SDS, Haldiram's) shows everything of that brand",
            "Belongs to has a small search icon: find any group (Spices, Milk, Rice…) when the suggestions are wrong, or make a new one",
        ),
    ),
    Release(
        "0.9.48", "17 Nov 2026",
        listOf("Stock rows show the brand chip, like the food lists"),
    ),
    Release(
        "0.9.47", "17 Nov 2026",
        listOf("Opening a food shows what's at home under its title: \"350 g in stock · Pantry\""),
    ),
    Release(
        "0.9.46", "16 Nov 2026",
        listOf(
            "For next week rows can be swiped away too, like To buy",
            "A swiped-away row comes back on its own once its stock changes (bought, added, recounted), so it can remind you again when it's needed; Show hidden brings them all back",
            "For next week counts stock kept in another unit of the food (10 wraps in pieces against a plan in grams)",
        ),
    ),
    Release(
        "0.9.45", "16 Nov 2026",
        listOf(
            "Add to stock instead, from Add: when the stock step is done (or cancelled) you're back on the same Add, with \"Added 170 g … to stock\" shown there",
            "A pack photo reads the pack's net weight, and adding it to stock starts at the whole pack (500 g) instead of 100 g",
        ),
    ),
    Release(
        "0.9.44", "16 Nov 2026",
        listOf(
            "Shoku AI keeps one memory per food, with what you changed last: \"Crushed Gobhi Soya Paneer: AI said 200 g, it was 1 serving (~150 g)\"",
            "Your correction stays even when you later keep one of the AI's guesses for that food; a newer correction replaces the amount",
            "Existing memories are tidied into this form (the gobhi 200 g match is folded into your correction)",
        ),
    ),
    Release(
        "0.9.43", "16 Nov 2026",
        listOf(
            "Photo logs have a place tag, At home or Eaten out: At home when your fridge batches are on the plate (or the dishes look like yours), Eaten out for a takeaway box or restaurant plate; one tap changes it",
            "Shoku AI learns your home dishes: each At home photo teaches what your plates, bowls and cutlery look like, and keeps a reference photo per kind of dish (up to 3); later photos on those dishes count as home food, from your fridge first",
            "Eaten out photos note it on the entries",
            "Correcting an entry on Today replaces the AI's earlier match for it (no more 200 g next to 1 serving)",
        ),
    ),
    Release(
        "0.9.42", "16 Nov 2026",
        listOf(
            "Settings › What Shoku AI has learned: every correction and match it remembers, how often and when; forget one (with Bring back) or everything",
        ),
    ),
    Release(
        "0.9.41", "16 Nov 2026",
        listOf(
            "Smarter memory for Shoku AI: keeps up to 500 things it learned (your corrections, and matches to your own foods you kept); the same correction again makes it stronger; each request gets the memories that fit it (words you typed, the same meal), your usual amounts at that meal and what's cooked in your fridge",
        ),
    ),
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
