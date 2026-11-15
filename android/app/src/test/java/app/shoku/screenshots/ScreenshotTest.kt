package app.shoku.screenshots

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.shoku.LaunchRequest
import app.shoku.LaunchRequests
import app.shoku.NoNetwork
import app.shoku.data.db.Meals
import app.shoku.data.net.Network
import app.shoku.ui.ShokuApp
import app.shoku.ui.theme.Appearance
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * README screenshots: the real app (ShokuApp and its view models) on the JVM, with the demo data in
 * DemoData seeded into the app's Room database. Phone size 412 x 915 dp at 2.625 (1080 x 2400 px).
 *
 *   ./gradlew :app:testDebugUnitTest -Pscreenshots      -> PNGs in docs/screenshots
 *
 * The network is switched off (Network.blocked) and, as a tripwire, every connection attempt is refused
 * and recorded by NoNetwork; the run fails if there was one.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-420dpi", application = ScreenshotApp::class)
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<ScreenshotApp>()

    @Before
    fun seed() {
        assertTrue(Network.blocked)
        // FileProvider caches its paths statically, but every Robolectric test gets a new data folder.
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        runBlocking { DemoData.seed(app) }
    }

    // ---------- Screens ----------

    @Test fun today() = screen("01-today") { waitFor("Breakfast") }

    @Test fun todayDark() = screen("02-today-dark", dark = true) { waitFor("Breakfast") }

    @Test fun add() = screen("03-add", LaunchRequest("add", Meals.SNACKS)) { waitFor("Describe") }

    @Test fun describe() = screen("04-describe", LaunchRequest("describe", Meals.DINNER)) {
        waitFor("Describe")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("2 rotis, a katori of rajma and a cucumber salad")
    }

    /** The amount sheet, as a scanned pack opens it (the barcode is one of the demo foods). */
    @Test fun scan() = screen("05-scan", LaunchRequest("scan", Meals.SNACKS, barcode = DemoData.DEMO_BARCODE)) { waitFor("Masala peanuts") }

    @Test fun addSearch() = screen("06-add-search", LaunchRequest("add", Meals.DINNER)) {
        type("paneer")
        waitFor("Palak paneer")
    }

    @Test fun globalSearch() = screen("07-search-everything", LaunchRequest("search")) {
        type("dal")
        waitFor("Dal tadka")
    }

    @Test fun libraryFoods() = screen("08-library-foods", LaunchRequest("library")) { waitFor("Masala chai") }

    @Test fun libraryStock() = screen("09-library-stock", LaunchRequest("stock")) { waitFor("Paneer") }

    @Test fun libraryStockSearch() = screen("10-library-stock-search", LaunchRequest("stock")) {
        waitFor("Paneer")
        type("masala")
        waitFor("Garam masala")
    }

    @Test fun batch() = screen("11-batch", LaunchRequest("library")) {
        waitFor("Batches")
        compose.onAllNodesWithText("Batches").onFirst().performClick()
        waitFor("Dal tadka")
        compose.onAllNodesWithText("Dal tadka").onFirst().performClick()
    }

    @Test fun planMeals() = screen("12-plan-meals", LaunchRequest("plan", arg = "meals")) { waitFor("Dal, rice and curd") }

    @Test fun planSupplements() = screen("13-plan-supplements", LaunchRequest("plan", arg = "supplements")) { waitFor("Omega-3") }

    @Test fun insights() = screen("14-insights", LaunchRequest("insights")) { waitFor("Insights") }

    @Test fun settingsAi() = screen("15-settings-ai", LaunchRequest("settings")) {
        waitFor("Settings")
        scrollToTop("AI this month")
    }

    @Test fun whatsNew() = screen("16-whats-new", LaunchRequest("settings")) {
        waitFor("Settings")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Version"))
        settle()
        // The row sits behind the navigation bar here, so call its click action instead of tapping.
        compose.onAllNodesWithText("What's new", substring = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    // ---------- Helpers ----------

    private fun screen(name: String, request: LaunchRequest? = null, dark: Boolean = false, steps: () -> Unit) {
        Appearance.mode = if (dark) "dark" else "light"
        LaunchRequests.pending.value = request
        compose.setContent { ShokuTheme { PhoneFrame { ShokuApp() } } }
        settle()
        steps()
        settle()
        val file = File(outDir(), "$name.png")
        captureScreenRoboImage(file.path)
        cropTo1080x2400(file)
        assertEquals("connections attempted", emptyList<Any>(), NoNetwork.attempts.toList())
    }

    /** Lets Room's background queries, debounces on the main looper and animations finish. */
    private fun settle() {
        repeat(4) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            Thread.sleep(60)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            settleOnce()
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun settleOnce() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        Thread.sleep(20)
    }

    /** Scrolls the screen's list until the item holding [text] starts at the top. */
    private fun scrollToTop(text: String) {
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        for (i in 0 until 40) {
            list.performScrollToIndex(i)
            settleOnce()
            val top = compose.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top ?: continue
            if (top < 400f) return
        }
        error("Couldn't scroll $text to the top")
    }

    private fun type(text: String) {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
        settle()
    }

    private fun outDir(): File =
        File(System.getProperty("shoku.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    /** 412 x 915 dp at 2.625 rounds to a pixel or two over 1080 x 2400; trim the edges evenly. */
    private fun cropTo1080x2400(file: File) {
        val img = ImageIO.read(file)
        if (img.width == 1080 && img.height == 2400) return
        val w = minOf(1080, img.width)
        val h = minOf(2400, img.height)
        val out = img.getSubimage((img.width - w) / 2, (img.height - h) / 2, w, h)
        ImageIO.write(out, "png", file)
    }

    companion object {
        @BeforeClass @JvmStatic fun guard() = NoNetwork.install()

        @AfterClass @JvmStatic fun unguard() {
            val tried = NoNetwork.attempts.toList()
            NoNetwork.uninstall()
            assertEquals("connections attempted", emptyList<Any>(), tried)
        }
    }
}

/** A plain status bar and gesture handle, so the screens read as a phone (Robolectric draws no system bars). */
@Composable
private fun PhoneFrame(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(ShokuColors.Paper)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("9:41", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Ink)
            Spacer(Modifier.weight(1f))
            listOf(Icons.Rounded.SignalCellularAlt, Icons.Rounded.Wifi, Icons.Rounded.BatteryFull).forEach {
                Icon(it, null, Modifier.size(16.dp), tint = ShokuColors.Ink)
            }
        }
        Box(Modifier.weight(1f)) { content() }
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 108.dp, height = 4.dp).clip(CircleShape).background(ShokuColors.Ink))
        }
    }
}
