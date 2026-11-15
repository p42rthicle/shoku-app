package app.shoku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import app.shoku.ui.ShokuApp
import app.shoku.ui.theme.ShokuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        app.shoku.ui.theme.Appearance.mode = getSharedPreferences("app", MODE_PRIVATE).getString("theme", "system") ?: "system"
        if (savedInstanceState == null) handle(intent)
        setContent {
            ShokuTheme {
                ShokuApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Taps on reminders: open Add (optionally with Describe) for a meal, or a tab. */
    private fun handle(intent: android.content.Intent?) {
        intent?.data?.let { uri -> DeepLinks.parse(uri)?.let { LaunchRequests.pending.value = it }; intent.data = null; return }
        val open = intent?.getStringExtra(app.shoku.data.notify.Reminders.EXTRA_OPEN) ?: return
        LaunchRequests.pending.value = LaunchRequest(open, intent.getStringExtra(app.shoku.data.notify.Reminders.EXTRA_MEAL), intent.getStringExtra("barcode"))
        intent.removeExtra(app.shoku.data.notify.Reminders.EXTRA_OPEN)
    }
}

/** Something a notification asked the app to show; consumed once by ShokuApp. */
/** [barcode]: open Add as if the scanner had read it (used to test the scan flow without a camera). */
data class LaunchRequest(val open: String, val meal: String? = null, val barcode: String? = null, val arg: String? = null)

/**
 * shoku:// links, for app shortcuts, notifications and anything else that wants to open a screen:
 *
 *   shoku://today[/2026-11-14]          Today, optionally a date
 *   shoku://add[?meal=lunch]            Add (meal by time if not given)
 *   shoku://describe[?meal=…]           Add with Describe open
 *   shoku://plate[?meal=…]              Add with plate photos open
 *   shoku://scan[?barcode=…]            Add with the barcode scanner (or a barcode as if scanned)
 *   shoku://plan[/meals|supplements|stock]
 *   shoku://library[?q=…]               Library, optionally searching
 *   shoku://insights   shoku://settings   shoku://guide   shoku://cook (pick a dish to cook a batch)
 */
object DeepLinks {
    fun parse(uri: android.net.Uri): LaunchRequest? {
        if (uri.scheme != "shoku") return null
        val meal = uri.getQueryParameter("meal")?.let { m -> app.shoku.data.db.Meals.ALL.firstOrNull { it.equals(m, ignoreCase = true) } }
        val first = uri.pathSegments.firstOrNull()
        return when (uri.host?.lowercase()) {
            null, "", "today" -> LaunchRequest("today", arg = first)
            "add" -> LaunchRequest("add", meal)
            "describe" -> LaunchRequest("describe", meal)
            "plate" -> LaunchRequest("plate", meal)
            "scan" -> LaunchRequest("scan", meal, uri.getQueryParameter("barcode"))
            "plan" -> LaunchRequest("plan", arg = first)
            "library" -> LaunchRequest("library", arg = uri.getQueryParameter("q"))
            "insights", "settings", "guide", "cook", "stock", "search" -> LaunchRequest(uri.host!!.lowercase())
            else -> null
        }
    }
}

object LaunchRequests {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<LaunchRequest?>(null)
}
