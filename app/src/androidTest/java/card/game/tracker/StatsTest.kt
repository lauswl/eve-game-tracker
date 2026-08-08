package card.game.tracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import card.game.tracker.data.Repository
import card.game.tracker.data.StatKind
import card.game.tracker.data.StatUnit
import card.game.tracker.data.TrackerDb
import card.game.tracker.domain.Scoring
import card.game.tracker.domain.Shape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The numbers behind the cards.
 *
 * A wrong leaderboard is worse than no leaderboard — it is the thing people
 * quote at each other for a year — so every card's headline number is pinned
 * here rather than eyeballed on a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class StatsTest {

    private lateinit var db: TrackerDb
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, TrackerDb::class.java).build()
        repo = Repository(db)
        runBlocking { repo.seedIfEmpty() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun game(key: String) = repo.enabledGames().first().first { it.gameKey == key }
    private suspend fun gambioId() = game("gambio").id
    private suspend fun durakId() = game("durak").id

    /** Plays one session of Gambio and ends it. */
    private suspend fun night(players: List<Long>, scores: Map<Long, Int>) {
        val s = repo.startSession(gambioId(), players)
        repo.recordScoreRound(s, scores)
        repo.endSession(s)
    }

    private suspend fun gambio() = repo.stats().games.single { it.gameId == gambioId() }

    @Test
    fun theRunningTotalIsCumulativeNotPerSession() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        night(listOf(anna, ben), mapOf(anna to 3, ben to 9))
        night(listOf(anna, ben), mapOf(anna to 4, ben to 1))
        night(listOf(anna, ben), mapOf(anna to 5, ben to 2))

        val g = gambio()
        assertEquals(listOf(3, 7, 12), g.series.single { it.name == "Anna" }.cumulative)
        assertEquals(listOf(9, 10, 12), g.series.single { it.name == "Ben" }.cumulative)
    }

    @Test
    fun aPlayerWhoSatOneOutHoldsTheirLineFlat() = runBlocking {
        // Absence is not a result. Dropping to zero for a session nobody played
        // would draw a cliff into the chart that never happened.
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        night(listOf(anna, ben), mapOf(anna to 5, ben to 5))
        night(listOf(anna), mapOf(anna to 4))
        night(listOf(anna, ben), mapOf(anna to 1, ben to 6))

        val g = gambio()
        assertEquals(listOf(5, 5, 11), g.series.single { it.name == "Ben" }.cumulative)
        assertEquals(listOf(5, 9, 10), g.series.single { it.name == "Anna" }.cumulative)
    }

    @Test
    fun streaksCountConsecutiveWinsAndResetOnALoss() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        // Gambio is low-wins: Anna takes the first two, Ben the third
        night(listOf(anna, ben), mapOf(anna to 1, ben to 9))
        night(listOf(anna, ben), mapOf(anna to 1, ben to 9))
        night(listOf(anna, ben), mapOf(anna to 9, ben to 1))

        val records = gambio().records.associateBy { it.name }
        assertEquals(0, records["Anna"]!!.currentStreak)   // just lost one
        assertEquals(2, records["Anna"]!!.bestStreak)
        assertEquals(1, records["Ben"]!!.currentStreak)
    }

    /**
     * A points card is per session, and this is why.
     *
     * Anna played two sessions and gave away four points; Ben played four and
     * gave away six. On a running total Ben looks the better Gambio player by
     * two points, which is not a fact about Gambio — it is a fact about how
     * often he turned up. Per session, Anna is ahead, which is the truth.
     */
    @Test
    fun aPointsCardRanksPerSessionSoTurningUpOftenCannotWinIt() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        repeat(2) { night(listOf(anna), mapOf(anna to 2)) }
        repeat(4) { night(listOf(ben), mapOf(ben to 1)) }
        // Anna: 4 points over 2 sessions = 2.0. Ben: 4 over 4 = 1.0, and low wins.
        val g = gambio()
        assertEquals(StatKind.POINTS, g.kind)
        assertEquals("Ben", g.records.first().name)
        assertEquals(1.0, g.records.first().perSession, 0.001)
        assertEquals(2.0, g.records.single { it.name == "Anna" }.perSession, 0.001)
    }

    @Test
    fun durakCountsHowOftenYouWereTheFoolAsAShareOfRounds() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val s = repo.startSession(durakId(), listOf(anna, ben))
        repo.recordLoserRound(s, ben)
        repo.recordLoserRound(s, ben)
        repo.recordLoserRound(s, anna)
        repo.recordLoserRound(s, ben)
        repo.endSession(s)

        val g = repo.stats().games.single { it.gameId == durakId() }
        assertEquals(StatKind.LOSS_RATE, g.kind)
        assertEquals(4, g.rounds)
        val byName = g.records.associateBy { it.name }
        assertEquals(0.25, byName["Anna"]!!.lossRate, 0.001)
        assertEquals(0.75, byName["Ben"]!!.lossRate, 0.001)
        // fewest losses first
        assertEquals("Anna", g.records.first().name)
    }

    @Test
    fun aRankingCardAveragesThePlaceRatherThanTotallingThePoints() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val cilli = repo.addPlayer("Cilli")
        repo.changeScoring(game("durak").copy(shape = Shape.RANKING))

        val s = repo.startSession(durakId(), listOf(anna, ben, cilli))
        repo.recordScoreRound(s, Scoring.rankingPoints(listOf(anna, ben, cilli)))
        repo.recordScoreRound(s, Scoring.rankingPoints(listOf(anna, cilli, ben)))
        repo.endSession(s)

        val g = repo.stats().games.single { it.gameId == durakId() }
        assertEquals(StatKind.PLACE, g.kind)
        val byName = g.records.associateBy { it.name }
        assertEquals(1.0, byName["Anna"]!!.averagePlace, 0.001)   // first, first
        assertEquals(2.5, byName["Ben"]!!.averagePlace, 0.001)    // second, third
        assertEquals(2.5, byName["Cilli"]!!.averagePlace, 0.001)  // third, second
        assertEquals(1.0, byName["Anna"]!!.roundWinRate, 0.001)
        assertEquals("Anna", g.records.first().name)
    }

    @Test
    fun aWinnerTakesAPointCardCountsRoundsWon() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        repo.changeScoring(game("durak").copy(shape = Shape.WINNER_ONLY))

        val s = repo.startSession(durakId(), listOf(anna, ben))
        repo.recordScoreRound(s, Scoring.winnerPoints(anna, listOf(anna, ben)))
        repo.recordScoreRound(s, Scoring.winnerPoints(anna, listOf(anna, ben)))
        repo.recordScoreRound(s, Scoring.winnerPoints(ben, listOf(anna, ben)))
        repo.endSession(s)

        val g = repo.stats().games.single { it.gameId == durakId() }
        assertEquals(StatKind.WIN_RATE, g.kind)
        val byName = g.records.associateBy { it.name }
        assertEquals(2, byName["Anna"]!!.roundsWon)
        assertEquals(3, byName["Anna"]!!.rounds)
        assertEquals(1, byName["Ben"]!!.roundsWon)
        assertEquals("Anna", g.records.first().name)
    }

    @Test
    fun changingHowAGameIsScoredTakesItsHistoryWithIt() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val s = repo.startSession(durakId(), listOf(anna))
        repo.recordLoserRound(s, anna)
        repo.endSession(s)
        assertEquals(1, repo.stats().games.count { it.gameId == durakId() })

        repo.changeScoring(game("durak").copy(shape = Shape.RANKING))
        assertEquals(0, repo.stats().games.count { it.gameId == durakId() })
    }

    @Test
    fun eachGameCarriesTheUnitItActuallyCounts() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        night(listOf(anna, ben), mapOf(anna to 1, ben to 2))
        val d = repo.startSession(durakId(), listOf(anna, ben))
        repo.recordLoserRound(d, ben)
        repo.endSession(d)

        val games = repo.stats().games.associateBy { it.name }
        assertEquals(StatUnit.POINTS, games["Gambio"]!!.unit)
        assertEquals(StatUnit.LOSSES, games["Durak"]!!.unit)
        assertTrue(games["Gambio"]!!.lowerIsBetter)
        // Only the two units where a running total is a real quantity plot one.
        assertTrue(games["Gambio"]!!.plots)
        assertTrue(!games["Durak"]!!.plots)
    }

    @Test
    fun statsRecomputeWhenASessionEndsWithoutBeingAsked() = runBlocking {
        // The Stats tab must be populated before it is opened, not because
        // opening it kicked off a query.
        val anna = repo.addPlayer("Anna")
        assertTrue(repo.statsFlow().first().games.isEmpty())

        night(listOf(anna), mapOf(anna to 4))

        assertEquals(1, repo.statsFlow().first().games.size)
    }
}
