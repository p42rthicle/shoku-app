package app.shoku.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.Meals
import app.shoku.data.db.TemplateItem
import app.shoku.domain.Library
import app.shoku.domain.SLOT
import app.shoku.domain.mealHistory
import app.shoku.domain.templateTotals
import app.shoku.ui.components.BusyOverlay
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.Segmented
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.shown
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing
import java.time.LocalDate

private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/**
 * Meal menu: every option for one meal in one place. Your templates (tap day chips to use one on those
 * days, so meals mix and match across the week), meals you've eaten often (save as a template), and
 * AI ideas from your stock and habits (save the ones you like).
 */
@Composable
fun MealMenu(vm: PlanViewModel, library: Library, contentPadding: PaddingValues, onEdit: (app.shoku.data.db.MealTemplate) -> Unit) {
    BackHandler { vm.menuOpen = false }
    val plan by vm.plan.collectAsState()
    val logs by vm.logs.collectAsState()
    val ideas by vm.ideas.collectAsState()
    val working by vm.ideasWorking.collectAsState()
    val meal = vm.menuMeal
    val templates = plan.templates.filter { !it.deleted && it.meal == meal }
    val history = remember(logs, plan, meal, library) { library.mealHistory(logs, meal, LocalDate.now(), plan) }
    val mealIdeas = ideas?.takeIf { it.first == meal }?.second

    if (working) BusyOverlay("Thinking up ${meal.lowercase()} ideas…", hint = "From your stock and what you eat · usually 10 to 20 seconds", onCancel = vm::cancelIdeas)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen, end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { vm.menuOpen = false })
            Spacer(Modifier.height(14.dp))
            ScreenTitle("Meal menu", eyebrow = "Plan")
            Spacer(Modifier.height(4.dp))
            Text("Tap the days under a meal to use it then. Mix and match freely; Today can still swap any meal for the day.",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            Spacer(Modifier.height(12.dp))
            Segmented(Meals.ALL, Meals.ALL.indexOf(meal)) { vm.menuMeal = Meals.ALL[it] }
        }

        item { SectionLabel("Your ${meal.lowercase()} templates", trailing = "${templates.size}") }
        // Templates used on some day first; four until "See all" (Use this week adds a set each time).
        templates.sortedBy { plan.weekdaysOf(it.id).isEmpty() }.shown("menu-tpl-$meal" in vm.openLists, 4).forEach { t ->
            item(key = "t-" + t.id) {
                val items = plan.itemsOf(t.id)
                val days = plan.weekdaysOf(t.id).toSet()
                MenuCard(t.name, items, library, onClick = { onEdit(t) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..7).forEach { d ->
                            Box(Modifier.clip(CircleShape).clickable { vm.setDay(d, meal, if (d in days) null else t.id) }) {
                                StatusChip(DAYS[d - 1], if (d in days) ChipStyle.Filled else ChipStyle.Tinted)
                            }
                        }
                    }
                }
            }
        }
        if (templates.size > 4) item(key = "menu-tpl-more") { app.shoku.ui.components.SeeMoreChip(templates.size, 4, "menu-tpl-$meal" in vm.openLists, { vm.toggleList("menu-tpl-$meal") }) }
        if (templates.isEmpty()) item { Text("No ${meal.lowercase()} templates yet: save one from below.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted) }

        if (history.isNotEmpty()) {
            item { SectionLabel("From your history", trailing = "last 4 months") }
            history.shown("history-$meal" in vm.openLists, 3).forEachIndexed { i, h ->
                item(key = "h-$meal-$i") {
                    val items = h.items.mapIndexed { n, it -> TemplateItem("h$n", "h", it.refType, it.refId, if (it.refType == SLOT) "Batch in the fridge" else "", it.unit, it.qty, n) }
                    MenuCard(h.name, items, library, note = "Eaten ${h.times}× · last ${LocalDate.parse(h.lastDate).let { "${it.dayOfMonth}.${it.monthValue}." }}") {
                        SecondaryButton("Save as template", onClick = { vm.saveHistory(h) })
                    }
                }
            }
        }

        if (history.isNotEmpty()) item(key = "history-more") { app.shoku.ui.components.SeeMoreChip(history.size, 3, "history-$meal" in vm.openLists, { vm.toggleList("history-$meal") }) }
        item { SectionLabel("AI ideas", trailing = "~2–3¢ a time") }
        item {
            SecondaryButton(if (mealIdeas == null) "Suggest 3 ${meal.lowercase()} ideas" else "Suggest 3 more", onClick = { vm.suggest(meal) })
            vm.ideasError?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Over) }
        }
        mealIdeas?.drafts?.forEachIndexed { i, d ->
            item(key = "ai-$meal-$i") {
                MenuCard(d.template.name, d.items, library, note = "AI idea") {
                    SecondaryButton("Save as template", onClick = { vm.saveDraft(d) })
                }
            }
        }
        mealIdeas?.notes?.takeIf { it.isNotBlank() }?.let { n -> item { Text(n, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) } }
    }
}

/** One meal option: name, items with amounts, calories and protein, and its actions. */
@Composable
private fun MenuCard(
    name: String,
    items: List<TemplateItem>,
    library: Library,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val n = library.templateTotals(items)
    OutlinedCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted) }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${Math.round(n.kcal ?: 0.0)} kcal", style = MaterialTheme.typography.labelLarge)
                Text("${Math.round(n.protein ?: 0.0)} g protein", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            items.joinToString(" · ") { i -> if (i.refType == SLOT) "batch" else "${i.refId?.let { library.nameOf(i.refType, it) } ?: "?"} ${formatUnitQty(i.qty, i.unit)}" },
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        actions()
    }
}
