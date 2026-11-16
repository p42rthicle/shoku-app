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
            "Today's plan uses what you cooked: your rice batch stands in for the template's rice, and the sabzi slot takes a curry batch (never rice); the card lists what's from the fridge",
            "Fixed a freeze when logging with AI: the loader shows at once, the lookups run in the background, and a second tap is ignored",
            "Searching is much faster everywhere",
            "\"Usual for…\" and search favour what you've eaten lately, not habits from months ago",
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
