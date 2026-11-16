package app.shoku.ui.insights

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.shoku.ui.components.MealPhotoViewer
import app.shoku.ui.components.mealPhotos
import app.shoku.ShokuApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoku.domain.GoalState
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SquareIconButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SummaryCard
import app.shoku.ui.components.formatAmount
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuSpacing
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)

@Composable
fun InsightsScreen(vm: InsightsViewModel, contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val s by vm.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen,
            end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ShokuSpacing.Gap),
    ) {
        item {
            ScreenTitle(
                "Insights",
                eyebrow = s.month.format(monthFormat),
                actions = {
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month", onClick = { vm.shift(-1) })
                    SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month", onClick = { vm.shift(1) })
                },
            )
            val last = s.lastLoggedMonth
            val now = YearMonth.now()
            if (s.logged.isEmpty() && last != null && last != s.month) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.clip(CircleShape).clickable { vm.show(last) }) {
                    StatusChip("Nothing logged this month · go to ${last.format(monthFormat)}", ChipStyle.Tinted)
                }
            } else if (s.month != now) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.clip(CircleShape).clickable { vm.show(now) }) { StatusChip("Back to this month", ChipStyle.Tinted) }
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            SummaryCard(
                label = "Daily average",
                value = s.avgKcal?.let { formatAmount(it.toFloat()) } ?: "–",
                trailing = "kcal",
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.avgProtein?.let { "${formatAmount(it.toFloat())} g protein · ${formatAmount(s.avgFibre!!.toFloat())} g fibre, across ${s.complete.size} ${if (s.complete.size == 1) "day" else "days"}${if (s.todayLeftOut) " (today counts once it's over)" else ""}" }
                        ?: "No days logged in this month.",
                    style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Stat("Days logged", "${s.logged.size}", "of ${s.daysElapsed}", Modifier.weight(1f))
                    Stat("Protein met", "${s.proteinMet}", "days", Modifier.weight(1f))
                    Stat("On target", "${s.caloriesOnTarget}", "days", Modifier.weight(1f))
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text("Calories", style = MaterialTheme.typography.titleLarge)
                Text(
                    s.kcalGoal?.let { "Goal ${formatAmount(it.toFloat())} kcal · tap a day to open it" } ?: "Tap a day to open it",
                    style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                )
                Spacer(Modifier.height(16.dp))
                if (s.logged.isNotEmpty()) {
                    DailyBars(s.days, value = { it.kcal }, goal = { it.kcalGoal }, color = { calorieColor(it.calories) })
                    Spacer(Modifier.height(18.dp))
                }
                Heatmap(s.month, s.days, cell = { calorieCell(it.calories) }, onOpenDay = onOpenDay)
                Spacer(Modifier.height(14.dp))
                Legend(listOf("On target" to calorieCell(GoalState.Good), "Within 4%" to calorieCell(GoalState.Close), "Over" to calorieCell(GoalState.Bad), "No log" to legendNone))
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text("Protein", style = MaterialTheme.typography.titleLarge)
                Text(
                    s.proteinGoal?.let { "Goal ${formatAmount(it.toFloat())} g" } ?: "No goal set",
                    style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
                )
                Spacer(Modifier.height(16.dp))
                if (s.logged.isNotEmpty()) {
                    DailyBars(s.days, value = { it.protein }, goal = { it.proteinGoal }, color = { proteinColor(it.proteinState) })
                    Spacer(Modifier.height(18.dp))
                }
                Heatmap(s.month, s.days, cell = { proteinCell(it.proteinState) }, onOpenDay = onOpenDay)
                Spacer(Modifier.height(14.dp))
                Legend(listOf("Met" to proteinCell(GoalState.Good), "Within 10%" to proteinCell(GoalState.Close), "Short" to proteinCell(GoalState.Bad), "No log" to legendNone))
            }
        }

        item {
            val supp by vm.supplements.collectAsState()
            if (supp.supplements.isNotEmpty()) SupplementCard(s.month, supp, onSelect = { vm.suppFilter.value = it }, onOpenDay = onOpenDay)
        }

        item {
            // Meal photos saved with entries: swipe through them all.
            val shoku = LocalContext.current.applicationContext as ShokuApplication
            val all by shoku.repository.allEntries.collectAsState(initial = emptyList())
            val count = remember(all) { mealPhotos(all).size }
            var open by remember { mutableStateOf(false) }
            if (open) MealPhotoViewer(null, onDismiss = { open = false })
            OutlinedCard(Modifier.fillMaxWidth(), onClick = { open = true }) {
                Text("Meal photos", style = MaterialTheme.typography.titleMedium)
                Text(if (count == 0) "Photos of plates you log are kept here. Swipe through them." else "$count ${if (count == 1) "photo" else "photos"} · tap to swipe through them",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }

        item {
            val weights by vm.weights.collectAsState()
            WeightCard(weights, onLog = vm::logWeight, onDelete = vm::deleteWeight)
        }
    }
}

/** Like the calorie and protein heatmaps: one square per day, filled when every due supplement was ticked. */
@Composable
private fun SupplementCard(month: YearMonth, s: SupplementInsights, onSelect: (String?) -> Unit, onOpenDay: (LocalDate) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text("Supplements", style = MaterialTheme.typography.titleLarge)
        val taken = s.days.values.sumOf { it.taken }
        val due = s.days.values.sumOf { it.due }
        Text(
            "Streak ${s.streak} ${if (s.streak == 1) "day" else "days"} · this month $taken of $due doses",
            style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Box(Modifier.clip(CircleShape).clickable { onSelect(null) }) { StatusChip("All", if (s.selected == null) ChipStyle.Filled else ChipStyle.Tinted) } }
            items(s.supplements.size) { i ->
                val sup = s.supplements[i]
                Box(Modifier.clip(CircleShape).clickable { onSelect(sup.id) }) {
                    StatusChip(sup.name.substringBefore(" (").substringBefore(" +"), if (s.selected == sup.id) ChipStyle.Filled else ChipStyle.Tinted)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val stats = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val day = s.days[date]
            DayStat(date, (day?.taken ?: 0).toDouble(), (day?.due ?: 0).toDouble(), 0.0, logged = (day?.due ?: 0) > 0, kcalGoal = null, proteinGoal = null)
        }
        Heatmap(month, stats, cell = { suppCell(it.kcal.toInt(), it.protein.toInt()) }, onOpenDay = onOpenDay)
        Spacer(Modifier.height(14.dp))
        Legend(listOf("All taken" to suppCell(1, 1), "Some" to suppCell(1, 2), "Missed" to suppCell(0, 1), "Nothing due" to legendNone))
        if (s.selected == null && s.perSupplement.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                s.perSupplement.joinToString(" · ") { (name, t, d) -> "${name.substringBefore(" (").substringBefore(" +")} $t/$d" },
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
        }
    }
}

private fun suppCell(taken: Int, due: Int) = when {
    due == 0 -> CellStyle(ShokuColors.Tint, ShokuColors.Muted)
    taken >= due -> CellStyle(ShokuColors.Fill, ShokuColors.Paper)
    taken > 0 -> CellStyle(ShokuColors.Mid, ShokuColors.Paper)
    else -> CellStyle(ShokuColors.Paper, ShokuColors.Ink, ShokuColors.Ink)
}

// ---------- Colours per state ----------

private data class CellStyle(val bg: Color, val fg: Color, val border: Color? = null)

private val legendNone = CellStyle(ShokuColors.Tint, ShokuColors.Muted, ShokuColors.Line)

private fun calorieCell(state: GoalState) = when (state) {
    GoalState.Good -> CellStyle(ShokuColors.Fill, ShokuColors.Paper)
    GoalState.Close -> CellStyle(ShokuColors.Mid, ShokuColors.Paper)
    GoalState.Bad -> CellStyle(ShokuColors.Over, ShokuColors.Paper)
    GoalState.None -> CellStyle(ShokuColors.Tint, ShokuColors.Muted)
}

private fun proteinCell(state: GoalState) = when (state) {
    GoalState.Good -> CellStyle(ShokuColors.Fill, ShokuColors.Paper)
    GoalState.Close -> CellStyle(ShokuColors.Mid, ShokuColors.Paper)
    GoalState.Bad -> CellStyle(ShokuColors.Paper, ShokuColors.Ink, ShokuColors.Ink)
    GoalState.None -> CellStyle(ShokuColors.Tint, ShokuColors.Muted)
}

private fun calorieColor(state: GoalState) = when (state) {
    GoalState.Bad -> ShokuColors.Over
    GoalState.Close -> ShokuColors.Mid
    else -> ShokuColors.Fill
}

private fun proteinColor(state: GoalState) = when (state) {
    GoalState.Good -> ShokuColors.Fill
    GoalState.Close -> ShokuColors.Mid
    else -> ShokuColors.Planned
}

// ---------- Pieces ----------

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle.Stat)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** One bar per day with a dashed goal line. Days without logs leave a gap. */
@Composable
private fun DailyBars(days: List<DayStat>, value: (DayStat) -> Double, goal: (DayStat) -> Double?, color: (DayStat) -> Color) {
    val goals = days.mapNotNull(goal)
    val maxV = maxOf(days.maxOfOrNull(value) ?: 0.0, goals.maxOrNull() ?: 0.0) * 1.08
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (maxV <= 0) return@Canvas
        val slot = size.width / days.size
        val barW = slot * 0.62f
        days.forEachIndexed { i, d ->
            if (!d.logged) return@forEachIndexed
            val h = (value(d) / maxV * size.height).toFloat()
            drawRoundRect(
                color = color(d),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
        goals.lastOrNull()?.let { g ->
            val y = (size.height - g / maxV * size.height).toFloat()
            drawLine(
                color = ShokuColors.Muted,
                start = Offset(0f, y), end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
    }
}

@Composable
private fun Heatmap(month: YearMonth, days: List<DayStat>, cell: (DayStat) -> CellStyle, onOpenDay: (LocalDate) -> Unit) {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val lead = month.atDay(1).dayOfWeek.value - 1
    val slots: List<DayStat?> = List(lead) { null } + days
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = ShokuColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        slots.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d ->
                    if (d == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val style = cell(d)
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(shape)
                                .background(style.bg)
                                .then(if (style.border != null) Modifier.border(1.5.dp, style.border, shape) else Modifier)
                                .then(if (d.date == today) Modifier.border(2.dp, ShokuColors.Ink, shape) else Modifier)
                                .clickable { onOpenDay(d.date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.labelMedium, color = style.fg)
                        }
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, CellStyle>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (text, style) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(3.dp)
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(shape)
                        .background(style.bg)
                        .then(if (style.border != null) Modifier.border(1.dp, style.border, shape) else Modifier),
                )
                Spacer(Modifier.width(5.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        }
    }
}
