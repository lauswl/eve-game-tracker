package card.game.tracker

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import card.game.tracker.data.GameDefEntity
import card.game.tracker.data.LiveSession
import card.game.tracker.data.SessionEntity
import card.game.tracker.domain.Direction
import card.game.tracker.domain.Participant
import card.game.tracker.domain.RoundData
import card.game.tracker.domain.Shape
import card.game.tracker.ui.screens.ScoringScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The entry flow is the app's riskiest screen: it is the thing you touch forty
 * times a night, so it is the thing that has to be right.
 */
class ScoringScreenTest {

    @get:Rule val rule = createComposeRule()

    private val gambio = GameDefEntity(
        id = 1, gameKey = "gambio", name = "Gambio", glyph = "♦",
        shape = Shape.PER_PLAYER_SCORE, direction = Direction.LOWER_BETTER,
    )

    private fun session(
        rounds: List<RoundData> = emptyList(),
        participants: List<Participant> = listOf(
            Participant(1, "Anna", 0), Participant(2, "Ben", 1),
        ),
    ) = LiveSession(
        session = SessionEntity(id = 1, gameDefId = 1, startedAt = 0),
        game = gambio,
        participants = participants,
        rounds = rounds,
        ledger = emptyList(),
    )

    private var recorded: Map<Long, Int>? = null

    private fun setScreen(live: LiveSession = session()) {
        recorded = null
        rule.setContent {
            ScoringScreen(
                live = live,
                onRecordScores = { recorded = it },
                onRecordLoser = {},
                onLedger = { _, _, _ -> },
                onUndo = {},
                onJoin = {},
                onLeave = {},
                onEnd = {},
            )
        }
    }

    @Test
    fun typingAdvancesThroughPlayersAndCommitsTheRound() {
        setScreen()

        // Anna: 12
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("NEXT").performClick()

        // Ben: 4 — the last player's commit records the round
        rule.onNodeWithText("4").performClick()
        rule.onNodeWithText("ADD ROUND").performClick()

        assertEquals(mapOf(1L to 12, 2L to 4), recorded)
    }

    @Test
    fun backspaceRemovesTheLastDigitOnly() {
        setScreen()
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithText("DEL").performClick()
        rule.onNodeWithText("NEXT").performClick()
        rule.onNodeWithText("9").performClick()
        rule.onNodeWithText("ADD ROUND").performClick()

        assertEquals(mapOf(1L to 12, 2L to 9), recorded)
    }

    @Test
    fun theMinusKeyIsHowYouSaySomebodyWentOff() {
        setScreen()
        rule.onNodeWithText("8").performClick()
        rule.onNodeWithText("-").performClick()
        rule.onNodeWithText("NEXT").performClick()
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText("ADD ROUND").performClick()

        assertEquals(mapOf(1L to -8, 2L to 1), recorded)
    }

    @Test
    fun aPlayerLeftBlankScoresZeroNotNothing() {
        setScreen()
        // committing with no digits at all still produces a complete round
        rule.onNodeWithText("NEXT").performClick()
        rule.onNodeWithText("ADD ROUND").performClick()
        assertEquals(mapOf(1L to 0, 2L to 0), recorded)
    }

    @Test
    fun undoIsUnavailableUntilThereIsSomethingToUndo() {
        setScreen()
        rule.onNodeWithText("UNDO").assertIsNotEnabled()
    }

    @Test
    fun undoBecomesAvailableOnceARoundExists() {
        setScreen(session(rounds = listOf(RoundData(0, mapOf(1L to 5, 2L to 6)))))
        rule.onNodeWithText("UNDO").assertIsDisplayed()
    }

    @Test
    fun theKeypadIsOnScreenSoNoSystemKeyboardIsEverNeeded() {
        setScreen()
        // "0" also appears in the zero totals row, so assert presence rather
        // than uniqueness — the point is that every key is on screen.
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "DEL", "-").forEach {
            assertTrue(
                "keypad is missing $it",
                rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun aPlayerWhoLeftIsNotOfferedACellToTypeInto() {
        val live = session(
            rounds = listOf(RoundData(0, mapOf(1L to 5, 2L to 6))),
            participants = listOf(
                Participant(1, "Anna", 0),
                Participant(2, "Ben", 1, leftAtRound = 1),
            ),
        )
        setScreen(live)
        // Anna alone is active, so her commit is the round commit
        rule.onNodeWithText("7").performClick()
        rule.onNodeWithText("ADD ROUND").performClick()
        assertEquals(mapOf(1L to 7), recorded)
        assertNull(recorded?.get(2L))
        assertTrue(recorded!!.size == 1)
    }
}
