package app.shoku.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.shoku.data.ai.AiMemory
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.StatusChip
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.Instant
import java.time.ZoneId

/**
 * Settings › What Shoku AI has learned: every memory (corrections you made, and matches to your own foods
 * you kept), strongest and newest first. Tap × to forget one (Undo in the toast's place: a "Bring back" row);
 * "Forget everything" asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var memories by remember { mutableStateOf(AiMemory.all()) }
    var lastForgotten by remember { mutableStateOf<AiMemory.Memory?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    fun reload() { memories = AiMemory.all() }
    val sorted = memories.sortedWith(compareByDescending<AiMemory.Memory> { it.kind == AiMemory.FIX }.thenByDescending { it.count }.thenByDescending { it.at })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ShokuColors.Paper, shape = ShokuShapes.Sheet) {
        LazyColumn(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("What Shoku AI has learned", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Notes it reads with each describe or photo request: your corrections, and matches to your own foods you kept. " +
                        "Your usual amounts and the batches in your fridge are added from your log too. Forget anything that's wrong.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
            lastForgotten?.let { m ->
                item(key = "undo") {
                    Row(Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable {
                        AiMemory.restore(m); lastForgotten = null; reload()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Forgot: ${m.line.take(44)}${if (m.line.length > 44) "…" else ""}", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
                        Text("Bring back", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink)
                    }
                }
            }
            if (sorted.isEmpty()) item {
                Text("Nothing yet. Correct the AI in its review (another food or amount) and it learns from that.",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                item { Text("${sorted.size} ${if (sorted.size == 1) "memory" else "memories"}", style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted) }
                items(sorted, key = { it.key }) { m ->
                    Column {
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(m.line, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusChip(when (m.kind) { AiMemory.FIX -> "Correction"; AiMemory.DISH -> "Home dishes"; else -> "Match" }, if (m.kind == AiMemory.FIX) ChipStyle.Filled else ChipStyle.Tinted)
                                    val day = Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).toLocalDate()
                                    Text(
                                        listOfNotNull(m.meal?.lowercase(), if (m.count > 1) "seen ${m.count}×" else null,
                                            "${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.Close, "Forget this", tint = ShokuColors.Muted,
                                modifier = Modifier.clip(CircleShape).clickable {
                                    AiMemory.forget(m.key); lastForgotten = m; reload()
                                    if (m.kind == AiMemory.DISH) app.shoku.data.PhotoStore.removeHomeRef(m.line.removePrefix("Home dishes: "))
                                }.padding(8.dp).size(20.dp))
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (confirmAll) "Tap again to forget all ${sorted.size}" else "Forget everything",
                        style = MaterialTheme.typography.labelLarge, color = if (confirmAll) ShokuColors.Over else ShokuColors.Muted,
                        modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable {
                            if (confirmAll) {
                                AiMemory.forgetAll(); confirmAll = false; lastForgotten = null; reload()
                                android.widget.Toast.makeText(context, "Shoku AI forgot what it learned", android.widget.Toast.LENGTH_SHORT).show()
                            } else confirmAll = true
                        }.padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
