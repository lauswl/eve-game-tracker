package card.game.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import card.game.tracker.data.GameDefEntity
import card.game.tracker.domain.Shape
import card.game.tracker.ui.screens.HomeBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The home grid, and the one thing about it that is easy to break twice.
 *
 * Every other control in the app fires on touch-down, because a scorekeeper
 * wants the key to act the instant the finger lands. A game tile cannot: past
 * eight games the grid scrolls, and a tile that fires on contact means every
 * attempt to scroll past a game starts that game instead. There is no way to
 * reach game nine.
 */
class HomeScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun games(n: Int) = (1..n).map {
        GameDefEntity(
            id = it.toLong(), gameKey = "g$it", name = "Game $it", glyph = "★",
            shape = Shape.PER_PLAYER_SCORE, sortIndex = it,
        )
    }

    @Test
    fun aTileWaitsForTheLiftSoADragCanBecomeAScroll() {
        var started: Long? = null
        rule.setContent {
            Column(Modifier.fillMaxSize()) { HomeBody(games(4), null, { started = it }) }
        }
        rule.onNodeWithText("GAME 1").performTouchInput { down(center) }
        rule.waitForIdle()
        assertNull("a tile must not fire while the finger is still down", started)

        rule.onNodeWithText("GAME 1").performTouchInput { up() }
        // Navigation waits just long enough for the depressed tile to draw.
        rule.waitUntil(timeoutMillis = 1_000) { started != null }
        assertEquals(1L, started)
    }

    @Test
    fun draggingAScrollingGridScrollsItAndStartsNothing() {
        var started: Long? = null
        rule.setContent {
            Column(Modifier.fillMaxSize()) { HomeBody(games(12), null, { started = it }) }
        }
        rule.onNodeWithText("GAME 1").performTouchInput { swipeUp() }
        rule.waitForIdle()

        // The assertion that matters is the negative one. How far the grid
        // travelled depends on fling physics and is not worth pinning; that the
        // swipe did not deal anybody into a game is the bug.
        assertNull("swiping the grid must not deal anybody in", started)
    }
}
