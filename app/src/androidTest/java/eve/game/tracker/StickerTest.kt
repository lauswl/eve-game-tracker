package eve.game.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import eve.game.tracker.ui.components.TabBar
import eve.game.tracker.ui.components.Sticker
import eve.game.tracker.ui.components.StickerLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The control everything else is made of.
 *
 * The thing worth pinning is *when* it fires. `Modifier.clickable` fires on
 * lift and, inside a scrollable, only after a 150 ms delay — which is the bug
 * these tests exist to stop coming back.
 */
class StickerTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun anInstantStickerFiresOnTouchDownWithoutWaitingForTheLift() {
        var fired = 0
        rule.setContent {
            Sticker(onClick = { fired++ }) { StickerLabel("Go") }
        }
        // press and hold — no lift at all
        rule.onNodeWithText("GO").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(1, fired)
    }

    @Test
    fun aScrollSafeStickerWaitsForTheLift() {
        var fired = 0
        rule.setContent {
            Sticker(onClick = { fired++ }, instant = false) { StickerLabel("Go") }
        }
        rule.onNodeWithText("GO").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals("must not fire while the finger is still down", 0, fired)

        rule.onNodeWithText("GO").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, fired)
    }

    @Test
    fun aDepartureDoesNotRunBeforeItsPressedFrameCanBeShown() {
        var fired = 0
        rule.setContent {
            Sticker(onClick = { fired++ }, departure = true) { StickerLabel("Go") }
        }

        // The quickest possible tap. A navigation callback here would dispose
        // the sticker before Compose ever drew its red/depressed state.
        rule.onNodeWithText("GO").performTouchInput { down(center); up() }
        assertEquals("departure must not run in the input event", 0, fired)

        rule.waitUntil(timeoutMillis = 1_000) { fired == 1 }
        assertEquals(1, fired)
    }

    @Test
    fun aDisabledStickerIgnoresBothDownAndUp() {
        var fired = 0
        rule.setContent {
            Sticker(onClick = { fired++ }, enabled = false) { StickerLabel("Go") }
        }
        rule.onNodeWithText("GO").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(0, fired)
    }

    @Test
    fun everyBarSegmentSelectsItsOwnIndex() {
        val picked = mutableListOf<Int>()
        rule.setContent {
            Column {
                TabBar(listOf("Home", "Stats", "Setup"), selected = 0, onSelect = { picked += it })
            }
        }
        rule.onNodeWithText("SETUP").performClick()
        rule.onNodeWithText("STATS").performClick()
        assertEquals(listOf(2, 1), picked)
    }
}
