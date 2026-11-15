package app.shoku.ui.insights

import app.shoku.ui.dayMonth
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.StatusChip
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.shoku.data.db.WeightEntry
import app.shoku.ui.components.LabeledField
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.toAmount
import app.shoku.ui.formatQty
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Average of the weigh-ins in the 7 days up to each date: smooths out water swings. */
fun weightTrend(weights: List<WeightEntry>): List<Pair<LocalDate, Double>> {
    val sorted = weights.filter { !it.deleted }.sortedBy { it.date }.map { LocalDate.parse(it.date) to it.kg }
    return sorted.map { (d, _) ->
        d to sorted.filter { (o, _) -> !o.isAfter(d) && ChronoUnit.DAYS.between(o, d) < 7 }.map { it.second }.average()
    }
}

/** Weigh-ins for the last 90 days: today's trend, change over 30 days, a line chart and "Log weight". */
@Composable
fun WeightCard(weights: List<WeightEntry>, onLog: (LocalDate, Double) -> Unit, onDelete: (WeightEntry) -> Unit = {}) {
    // The day being logged or fixed (null = closed).
    var logging by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val recent = weights.filter { it.date >= today.minusDays(90).toString() }
    val trend = weightTrend(recent)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text("Weight", style = MaterialTheme.typography.titleLarge)
        if (trend.isEmpty()) {
            Text("Weigh yourself in the morning, a few times a week. The trend line evens out daily swings.", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
        } else {
            val now = trend.last().second
            val monthAgo = trend.lastOrNull { !it.first.isAfter(today.minusDays(30)) }?.second ?: trend.first().second
            val change = now - monthAgo
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatQty(Math.round(now * 10) / 10.0), style = NumberStyle.Row)
                Text(" kg trend", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            }
            Text(
                (if (change >= 0) "+" else "−") + formatQty(Math.round(kotlin.math.abs(change) * 10) / 10.0) + " kg in 30 days · last weigh-in " + formatQty(recent.last().kg) + " kg",
                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
            )
            if (trend.size >= 2) {
                Spacer(Modifier.height(12.dp))
                WeightChart(recent.sortedBy { it.date }.map { LocalDate.parse(it.date) to it.kg }, trend)
            }
        }
        // The last few weigh-ins: tap one to fix or delete it.
        val last = weights.filter { !it.deleted }.sortedByDescending { it.date }.take(4)
        if (last.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                last.forEach { w ->
                    Box(Modifier.clip(CircleShape).clickable { logging = LocalDate.parse(w.date) }) {
                        StatusChip("${LocalDate.parse(w.date).dayMonth()} · ${formatQty(w.kg)} kg", ChipStyle.Tinted)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Log weight", onClick = { logging = today })
    }
    logging?.let { day ->
        val existing = weights.firstOrNull { it.date == day.toString() && !it.deleted }
        var date by remember(day) { mutableStateOf(day) }
        var text by remember(day) { mutableStateOf((existing ?: weights.lastOrNull())?.kg?.let { formatQty(it) } ?: "") }
        var picking by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { logging = null },
            containerColor = ShokuColors.Paper,
            title = { Text(if (date == today) "Today's weight" else "Weight on ${date.dayMonth()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("Weight", text, { text = it }, Modifier.fillMaxWidth(), numeric = true, suffix = "kg")
                    Text(
                        "Day: ${if (date == today) "today" else date.dayMonth()} · change", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink,
                        modifier = Modifier.clip(ShokuShapes.Button).clickable { picking = true }.padding(vertical = 6.dp),
                    )
                    if (existing != null) Text(
                        "Delete this weigh-in", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Over,
                        modifier = Modifier.clip(ShokuShapes.Button).clickable { onDelete(existing); logging = null }.padding(vertical = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toAmount()?.takeIf { it in 25.0..300.0 }?.let { onLog(date, it); logging = null }
                }) { Text("Save", color = ShokuColors.Ink) }
            },
            dismissButton = { TextButton(onClick = { logging = null }) { Text("Cancel", color = ShokuColors.Muted) } },
        )
        if (picking) app.shoku.ui.components.DatePick(date, onPick = { if (!it.isAfter(today)) date = it }, onDismiss = { picking = false })
    }
}

/** Dots for weigh-ins, a line for the 7-day trend. */
@Composable
private fun WeightChart(points: List<Pair<LocalDate, Double>>, trend: List<Pair<LocalDate, Double>>) {
    val first = points.first().first
    val days = ChronoUnit.DAYS.between(first, points.last().first).coerceAtLeast(1).toFloat()
    val lo = points.minOf { it.second } - 0.5
    val hi = points.maxOf { it.second } + 0.5
    val dot = ShokuColors.Line
    val line = ShokuColors.Ink
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        fun at(d: LocalDate, kg: Double) = Offset(
            ChronoUnit.DAYS.between(first, d) / days * size.width,
            ((hi - kg) / (hi - lo)).toFloat() * size.height,
        )
        points.forEach { (d, kg) -> drawCircle(dot, radius = 4.dp.toPx(), center = at(d, kg)) }
        val path = Path()
        trend.forEachIndexed { i, (d, kg) -> val p = at(d, kg); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
    }
}
