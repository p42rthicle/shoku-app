package app.shoku.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.Meals
import app.shoku.domain.Library
import app.shoku.domain.Nutrients
import app.shoku.domain.SLOT
import app.shoku.domain.templateTotals
import app.shoku.ui.components.BusyOverlay
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Plan my week with AI: an optional wish, one request (~3-5 cents) with the loader, then the proposal to
 * review (meals, their days, daily totals) before using it. Nothing changes until "Use this week".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekPlanSheet(vm: PlanViewModel, library: Library) {
    val proposal by vm.week.collectAsState()
    val working by vm.weekWorking.collectAsState()
    var wish by remember { mutableStateOf("") }
    if (working) BusyOverlay("Planning your week…", hint = "From your library, stock, habits and goals · usually 20 to 40 seconds", onCancel = vm::cancelWeek)

    ModalBottomSheet(
        onDismissRequest = { vm.weekSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Plan my week with AI", style = MaterialTheme.typography.headlineSmall)
            val p = proposal
            if (p == null) {
                Text(
                    "Shoku AI plans every meal for Monday to Sunday from your foods, what's at home and what you usually eat, within your goals. You review it before anything changes. About 3–5¢.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
                LabeledField("Anything to aim for? (optional)", wish, { wish = it }, Modifier.fillMaxWidth(), placeholder = "e.g. use up the paneer, more fibre at dinner")
                vm.weekError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Over) }
                PrimaryButton("Plan my week", onClick = { vm.planWeek(wish) })
            } else {
                // Daily totals of the proposal (batch slots not counted).
                val perDay = (1..7).map { d ->
                    p.drafts.filter { d in it.weekdays }.fold(Nutrients.ZERO) { acc, t -> acc + library.templateTotals(t.items) }
                }
                Text(p.notes, style = MaterialTheme.typography.bodyMedium)
                if (p.skipped > 0) Text("${p.skipped} suggested items weren't in your library and were left out.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                Meals.ALL.forEach { meal ->
                    val ts = p.drafts.filter { it.template.meal == meal }
                    if (ts.isEmpty()) return@forEach
                    Spacer(Modifier.height(4.dp))
                    Text(meal, style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                    ts.forEach { d ->
                        val n = library.templateTotals(d.items)
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(d.template.name, style = MaterialTheme.typography.titleMedium)
                                Text(d.weekdays.sorted().joinToString(", ") { DAY[it - 1] }.ifEmpty { "no days" }, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                                Text(
                                    d.items.joinToString(" · ") { i -> if (i.refType == SLOT) "batch" else "${i.refId?.let { library.nameOf(i.refType, it) } ?: "?"} ${app.shoku.ui.formatUnitQty(i.qty, i.unit)}" },
                                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${Math.round(n.kcal ?: 0.0)} kcal\n${Math.round(n.protein ?: 0.0)} g protein", style = MaterialTheme.typography.labelLarge)
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Per day (without the batch)", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted)
                perDay.forEachIndexed { i, n ->
                    Row { Text(DAY[i], Modifier.width(48.dp)); Text("${Math.round(n.kcal ?: 0.0)} kcal · ${Math.round(n.protein ?: 0.0)} g protein", color = ShokuColors.Muted) }
                }
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Use this week", onClick = { vm.useWeek(p) })
                SecondaryButton("Discard", onClick = { vm.week.value = null })
                Text("Your current templates stay in the Meal menu; this only changes which one each day uses.", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }
    }
}
