package app.shoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.shoku.ui.formatQty
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/** The round tick used wherever several items are added or removed at once: filled = included. */
@Composable
fun IncludeTick(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (on) ShokuColors.Fill else ShokuColors.Paper)
            .then(if (!on) Modifier.border(1.5.dp, ShokuColors.Planned, CircleShape) else Modifier)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Rounded.Check, "Included", tint = ShokuColors.Paper, modifier = Modifier.size(16.dp)) }
}

/** One row of a [PickItemsSheet]. */
data class PickItem(val key: String, val title: String, val detail: String, val tag: String? = null, val kcal: Double? = null, val protein: Double? = null)

/**
 * Choose which of several items to act on (copy a meal, add the plan, remove planned…): all ticked to
 * start; untick what you don't want, then [action] (e.g. "Add 7 of 9").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickItemsSheet(
    title: String,
    subtitle: String?,
    items: List<PickItem>,
    action: (chosen: Int, of: Int) -> String,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "Untick anything you don't want.",
) {
    val on = remember(items) { mutableStateListOf<Boolean>().apply { items.forEach { add(true) } } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(subtitle, hint).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, it ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickable { on[i] = !on[i] }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IncludeTick(on[i], { on[i] = !on[i] })
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            NameWithTag(it.title, it.tag, MaterialTheme.typography.titleMedium, color = if (on[i]) ShokuColors.Ink else ShokuColors.Muted)
                            Text(it.detail, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1)
                        }
                        it.kcal?.let { k ->
                            Spacer(Modifier.width(10.dp))
                            Text("${formatQty(Math.round(k).toDouble())} kcal", style = NumberStyle.Row, color = if (on[i]) ShokuColors.Ink else ShokuColors.Muted)
                        }
                    }
                }
            }
            val n = on.count { it }
            Spacer(Modifier.height(10.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chosen = items.filterIndexed { i, _ -> on[i] }
                Text(
                    if (items.any { it.kcal != null }) "${formatQty(Math.round(chosen.sumOf { it.kcal ?: 0.0 }).toDouble())} kcal · ${formatQty(Math.round(chosen.sumOf { it.protein ?: 0.0 } * 10) / 10.0)} g protein" else "$n of ${items.size}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                Text(
                    if (n == items.size) "Untick all" else "Tick all",
                    style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                    modifier = Modifier.clip(ShokuShapes.Button).clickable { val all = n != items.size; on.indices.forEach { j -> on[j] = all } }.padding(8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (n == 0) "Nothing ticked" else action(n, items.size), onClick = {
                if (n > 0) onConfirm(items.filterIndexed { i, _ -> on[i] }.map { it.key }.toSet())
            })
        }
    }
}
