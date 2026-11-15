package app.shoku.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import app.shoku.ShokuApplication
import app.shoku.data.health.HealthExport
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.theme.ShokuColors
import kotlinx.coroutines.launch

/** Switch for the optional Health Connect export (eaten food and weight, one way). */
@Composable
fun HealthCard() {
    val app = LocalContext.current.applicationContext as ShokuApplication
    val export = app.health
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(export.enabled) }
    var status by remember { mutableStateOf<String?>(null) }

    fun start() = scope.launch {
        export.enabled = true
        export.resetCursor()
        on = true
        status = runCatching { "Exported ${export.run()} records" }.getOrElse { "Export failed: ${it.message}" }
    }

    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthExport.PERMISSIONS)) start() else status = "Permission not given"
    }
    LaunchedEffect(Unit) { if (on && !export.hasPermissions()) status = "Permission was removed; turn it on again" }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !export.available -> "Health Connect isn't available on this phone"
                        else -> status ?: "Sends eaten food and weigh-ins to Health Connect when you leave the app. Nothing is read back."
                    },
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted,
                )
            }
            Switch(
                checked = on,
                enabled = export.available,
                onCheckedChange = { want ->
                    if (!want) { export.enabled = false; on = false; status = null }
                    else scope.launch { if (export.hasPermissions()) start() else ask.launch(HealthExport.PERMISSIONS) }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = ShokuColors.Fill),
            )
        }
    }
}
