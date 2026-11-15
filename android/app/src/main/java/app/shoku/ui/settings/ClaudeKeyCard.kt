package app.shoku.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.shoku.data.ai.AiSettings
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/**
 * The user's own Claude API key. With a key saved, AI calls go straight from the phone to Anthropic
 * (direct mode); without one they go to the backend if this build has one. [onChanged] runs after a
 * save or remove, so the usage card can reload from the right place.
 */
@Composable
fun ClaudeKeyCard(onChanged: () -> Unit = {}) {
    val hasKey by AiSettings.hasKey.collectAsState()
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text("Claude API key", style = MaterialTheme.typography.titleLarge)
        Text(
            "Your key stays on this phone, and AI calls go straight from here to Anthropic.",
            style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        if (hasKey) {
            Text("Saved: ${AiSettings.maskedKey() ?: "…"}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(ShokuShapes.Tile).background(ShokuColors.Tint).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (text.isEmpty()) Text(if (hasKey) "Paste a new key to replace it" else "sk-ant-…", style = MaterialTheme.typography.titleMedium, color = ShokuColors.Planned)
                BasicTextField(
                    value = text,
                    onValueChange = { v -> text = v.filterNot { it.isWhitespace() } },
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = ShokuColors.Ink),
                    singleLine = true,
                    cursorBrush = SolidColor(ShokuColors.Ink),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.clip(ShokuShapes.Button).clickable { visible = !visible }.padding(10.dp)) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                    tint = ShokuColors.Muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (text.isNotEmpty()) {
                PrimaryButton("Save", onClick = {
                    AiSettings.saveKey(text)
                    text = ""
                    visible = false
                    onChanged()
                }, modifier = Modifier.weight(1f))
            }
            if (hasKey) {
                SecondaryButton("Remove", onClick = { AiSettings.removeKey(); onChanged() }, modifier = Modifier.weight(1f))
            }
            if (text.isEmpty() && !hasKey) {
                Text(
                    "Get a key at platform.claude.com. Calls are billed to your Anthropic account.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
        }
    }
}
