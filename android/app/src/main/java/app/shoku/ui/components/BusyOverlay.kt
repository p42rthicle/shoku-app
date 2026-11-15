package app.shoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/**
 * Blocks the screen while the AI works: a spinner and what it's doing. Back and taps outside do nothing
 * (so nothing changes under the request); [onCancel], when given, stops it.
 */
@Composable
fun BusyOverlay(message: String, hint: String = "Usually 5 to 15 seconds", onCancel: (() -> Unit)? = null, ai: Boolean = true) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.widthIn(min = 240.dp, max = 320.dp).clip(ShokuShapes.Card).background(ShokuColors.Paper).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(color = ShokuColors.Ink, trackColor = ShokuColors.Line, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            // An AI request says so plainly (a barcode lookup isn't one).
            if (ai) Text("Shoku AI", style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted)
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, textAlign = TextAlign.Center)
            if (onCancel != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cancel", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                    modifier = Modifier.clip(ShokuShapes.Button).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
