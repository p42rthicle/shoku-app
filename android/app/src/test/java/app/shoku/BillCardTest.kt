package app.shoku

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.shoku.ui.library.BillCard
import app.shoku.ui.theme.ShokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bill card on Stock: its buttons must act (0.9.57 broke "Review" and "OK": their trailing lambda went to the second button). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BillCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reviewOpensFromTheButtonAndTheCard() {
        var opened = 0
        // Called exactly as Stock calls it: a trailing lambda (0.9.57 sent that to the wrong button).
        compose.setContent { ShokuTheme { BillCard("Check 7 new items from your bill", "Already in stock.", "Review") { opened++ } } }
        compose.onNodeWithText("Review").performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Check 7 new items from your bill").performClick()
        assertEquals(2, opened)
    }

    @Test fun twoButtonsEachDoTheirOwnThing() {
        var ok = 0; var again = 0
        compose.setContent { ShokuTheme { BillCard("Already added?", "Nothing was added.", "OK", second = "Add it again", onSecond = { again++ }) { ok++ } } }
        compose.onNodeWithText("Add it again").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Already added?").performClick() // the card itself picks neither
        assertEquals(1, ok); assertEquals(1, again)
    }
}
