package app.shoku.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.BillImport
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.IncludeTick
import app.shoku.ui.components.NameWithTag
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.StatusChip
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/** "Open Food Facts", "Food database", "AI estimate" or "Your food": where a bill line's food came from. */
private fun sourceLabel(s: String) = when (s) {
    "off" -> "Open Food Facts"
    "database" -> "Food database"
    "estimate" -> "AI estimate"
    else -> "Your food"
}

/**
 * Check what a bill (or Say it) added: the new foods first, each to keep, edit, replace or remove; the ones
 * that matched your own foods folded below; and what wasn't food. Untick and remove, or all look right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillReviewSheet(
    bills: List<BillImport.Bill>,
    onEdit: (BillImport.Line) -> Unit,
    onReplace: (BillImport.Line) -> Unit,
    onRemove: (List<BillImport.Line>) -> Unit,
    onAddAnyway: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fresh = bills.flatMap { it.toReview }
    // The bills still being checked, and always the latest: their matched lines and what wasn't food.
    val open = BillImport.shown(bills)
    val mine = open.flatMap { b -> b.lines.filter { it.source == "yours" } }
    val skipped = open.flatMap { it.skipped }.distinct()
    val off = remember(fresh.size, mine.size) { mutableStateListOf<String>() } // unticked line ids
    var showMine by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<BillImport.Line?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("From your bill", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (fresh.isEmpty()) "Everything is checked." else "Already in stock. Tap one to keep, edit, replace or remove it; untick any you didn't want.",
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (fresh.isNotEmpty()) item(key = "new-label") { Label("New · ${fresh.size}") }
                fresh.forEachIndexed { i, l ->
                    item(key = "n-" + l.id) {
                        if (i > 0) Hairline()
                        LineRow(l, l.id !in off, { if (l.id in off) off.remove(l.id) else off.add(l.id) }) { acting = l }
                    }
                }
                if (mine.isNotEmpty()) item(key = "mine-label") {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { showMine = !showMine }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Added to your foods · ${mine.size}", Modifier.weight(1f))
                        Text(if (showMine) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted)
                    }
                }
                if (showMine) mine.forEachIndexed { i, l ->
                    item(key = "m-" + l.id) {
                        if (i > 0) Hairline()
                        LineRow(l, l.id !in off, { if (l.id in off) off.remove(l.id) else off.add(l.id) }) { acting = l }
                    }
                }
                if (skipped.isNotEmpty()) {
                    item(key = "skip-label") { Spacer(Modifier.height(10.dp)); Label("Not added (not food) · ${skipped.size}") }
                    skipped.forEach { s ->
                        item(key = "s-$s") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.clip(CircleShape).clickable { onAddAnyway(s) }) { StatusChip("Add anyway", ChipStyle.Tinted) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val all = fresh + mine
            if (off.isNotEmpty()) {
                PrimaryButton("Remove ${off.size} from stock", onClick = { onRemove(all.filter { it.id in off }); off.clear() })
            } else if (fresh.isNotEmpty()) {
                PrimaryButton("All look right", onClick = { BillImport.allLookRight(); onDismiss() })
            } else {
                PrimaryButton("Done", onClick = onDismiss)
            }
        }
    }
    acting?.let { l ->
        ModalBottomSheet(
            onDismissRequest = { acting = null },
            containerColor = ShokuColors.Paper,
            shape = ShokuShapes.Sheet,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NameWithTag(l.name, l.brand, MaterialTheme.typography.headlineSmall)
                Text("${l.amount} added · ${sourceLabel(l.source)} · on the bill: ${l.said}", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                if (l.source != "yours" && !l.reviewed) PrimaryButton("Looks right", onClick = { BillImport.looksRight(l); acting = null })
                SecondaryButton("Edit the amount or food", onClick = { acting = null; onEdit(l) })
                SecondaryButton("Replace with another food (search, scan or photo)", onClick = { acting = null; onReplace(l) })
                Text(
                    "Remove it from stock",
                    style = MaterialTheme.typography.labelLarge, color = ShokuColors.Over,
                    modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable { acting = null; onRemove(listOf(l)) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted, modifier = modifier.padding(vertical = 4.dp))

@Composable
private fun LineRow(l: BillImport.Line, on: Boolean, onTick: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IncludeTick(on, onTick)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NameWithTag(l.name, l.brand, MaterialTheme.typography.titleMedium, color = if (on) ShokuColors.Ink else ShokuColors.Muted)
            Text("${l.amount} · ${sourceLabel(l.source)} · ${l.said}", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
