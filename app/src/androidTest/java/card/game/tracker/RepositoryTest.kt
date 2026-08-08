package card.game.tracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import card.game.tracker.data.GameCatalog
import card.game.tracker.data.Repository
import card.game.tracker.data.TrackerDb
import card.game.tracker.domain.LedgerKind
import card.game.tracker.data.StatUnit
import card.game.tracker.domain.Scoring
import card.game.tracker.domain.Shape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {

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

    private suspend fun gambioId() = repo.enabledGames().first().first { it.gameKey == "gambio" }.id
    private suspend fun pokerId() = repo.enabledGames().first().first { it.gameKey == "poker" }.id

    @Test
    fun seedsTheTestGamesOnce() = runBlocking {
        repo.seedIfEmpty()
        val games = repo.enabledGames().first()
        assertEquals(5, games.size)
        // One of each shape a round can be stored in, so the demo data and the
        // tests both exercise every card the Stats tab knows how to draw.
        assertEquals(
            setOf("skat", "poker", "gambio", "durak", "uno"),
            games.map { it.gameKey }.toSet(),
        )
    }

    @Test
    fun recordsRoundsAndComputesTotals() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val s = repo.startSession(gambioId(), listOf(anna, ben))

        repo.recordScoreRound(s, mapOf(anna to 12, ben to 4))
        repo.recordScoreRound(s, mapOf(anna to 7, ben to 19))

        val live = repo.liveSession(s).first()!!
        assertEquals(2, live.roundCount)
        val totals = live.standings.associate { it.name to it.total }
        assertEquals(19, totals["Anna"])
        assertEquals(23, totals["Ben"])
        // Gambio is low-wins, so Anna leads
        assertEquals("Anna", Scoring.leaders(live.standings).single().name)
    }

    @Test
    fun midSessionJoinLeavesEarlierRoundsEmptyNotZero() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val dora = repo.addPlayer("Dora")
        val s = repo.startSession(gambioId(), listOf(anna))

        repo.recordScoreRound(s, mapOf(anna to 10))
        repo.addPlayerToSession(s, dora)
        repo.recordScoreRound(s, mapOf(anna to 5, dora to 3))

        val live = repo.liveSession(s).first()!!
        val doraP = live.participants.first { it.name == "Dora" }
        assertEquals(1, doraP.joinedAtRound)
        // absent for round 0 — a dash, never a zero
        assertNull(Scoring.cellValue(live.config, live.rounds[0], doraP))
        assertEquals(3, Scoring.cellValue(live.config, live.rounds[1], doraP))
        val dStanding = live.standings.first { it.name == "Dora" }
        assertEquals(3, dStanding.total)
        assertEquals(1, dStanding.roundsPlayed)
    }

    @Test
    fun leavingKeepsHistoryAndStopsCounting() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val s = repo.startSession(gambioId(), listOf(anna, ben))

        repo.recordScoreRound(s, mapOf(anna to 4, ben to 6))
        repo.removePlayerFromSession(s, ben)
        repo.recordScoreRound(s, mapOf(anna to 4))

        val live = repo.liveSession(s).first()!!
        val benP = live.participants.first { it.name == "Ben" }
        assertEquals(1, benP.leftAtRound)
        assertEquals(6, live.standings.first { it.name == "Ben" }.total)
        assertEquals(1, live.standings.first { it.name == "Ben" }.roundsPlayed)
    }

    @Test
    fun undoDropsOnlyTheLastRound() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val s = repo.startSession(gambioId(), listOf(anna))
        repo.recordScoreRound(s, mapOf(anna to 10))
        repo.recordScoreRound(s, mapOf(anna to 5))

        repo.undoLastRound(s)

        val live = repo.liveSession(s).first()!!
        assertEquals(1, live.roundCount)
        assertEquals(10, live.standings.first().total)
    }

    @Test
    fun correctingACellEditsInPlaceAndKeepsTheRound() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val s = repo.startSession(gambioId(), listOf(anna))
        repo.recordScoreRound(s, mapOf(anna to 100))   // fat-fingered

        repo.correctScore(s, roundIndex = 0, playerId = anna, value = 10)

        val live = repo.liveSession(s).first()!!
        assertEquals(1, live.roundCount)
        assertEquals(10, live.standings.first().total)
    }

    @Test
    fun skatIsScoredByHandLikeEveryOtherPointsGame() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val skat = repo.enabledGames().first().first { it.gameKey == "skat" }
        val s = repo.startSession(skat.id, listOf(anna, ben))

        repo.recordScoreRound(s, mapOf(anna to 24, ben to 0))     // Anna made it
        repo.recordScoreRound(s, mapOf(anna to -40, ben to 0))    // Anna went off

        val live = repo.liveSession(s).first()!!
        assertEquals(-16, live.standings.first { it.name == "Anna" }.total)
        assertEquals(0, live.standings.first { it.name == "Ben" }.total)
        // high wins, so Ben leads on nil
        assertEquals("Ben", Scoring.leaders(live.standings).single().name)
    }

    @Test
    fun durakCountsLosses() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val durak = repo.enabledGames().first().first { it.gameKey == "durak" }
        val s = repo.startSession(durak.id, listOf(anna, ben))

        repo.recordLoserRound(s, ben)
        repo.recordLoserRound(s, ben)
        repo.recordLoserRound(s, null)   // drawn

        val live = repo.liveSession(s).first()!!
        assertEquals(0, live.standings.first { it.name == "Anna" }.total)
        assertEquals(2, live.standings.first { it.name == "Ben" }.total)
        assertEquals("Anna", Scoring.leaders(live.standings).single().name)
    }

    /**
     * The Durak setting decides how the NEXT night is kept, never how the last
     * one is read.
     *
     * Counting losses and counting points store completely different rows, so a
     * night read back under the other setting is a night of nothing. Without the
     * shape stamped on the session, changing your mind in Setup would quietly
     * flatten every Durak evening you had ever played to zero — and it would
     * look like the app had simply lost them.
     */
    @Test
    fun switchingDurakToPointsLeavesTheNightsAlreadyPlayedAlone() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val durak = repo.enabledGames().first().first { it.gameKey == "durak" }
        val s = repo.startSession(durak.id, listOf(anna, ben))
        repo.recordLoserRound(s, ben)
        repo.recordLoserRound(s, ben)
        repo.endSession(s)

        repo.updateGame(durak.copy(shape = Shape.PER_PLAYER_SCORE))

        val live = repo.liveSession(s).first()!!
        assertEquals(Shape.LOSER_ONLY, live.shape)
        assertEquals(2, live.standings.first { it.name == "Ben" }.total)
        assertEquals("Anna", Scoring.leaders(live.standings).single().name)

        // ...and it is still a losses card in the stats, still won by fewest
        val card = repo.stats().games.first { it.gameId == durak.id }
        assertEquals(StatUnit.LOSSES, card.unit)
        assertEquals(true, card.lowerIsBetter)
        assertEquals("Anna", card.records.first().name)

        // the next night is kept the new way
        val s2 = repo.startSession(durak.id, listOf(anna, ben))
        assertEquals(Shape.PER_PLAYER_SCORE, repo.liveSession(s2).first()!!.shape)
    }

    @Test
    fun pokerLedgerNetsOutAndFlagsAnUnbalancedTable() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val s = repo.startSession(pokerId(), listOf(anna, ben))

        repo.addLedger(s, anna, LedgerKind.BUY_IN, 1000)
        repo.addLedger(s, ben, LedgerKind.BUY_IN, 1000)
        repo.addLedger(s, anna, LedgerKind.CASH_OUT, 1500)

        var live = repo.liveSession(s).first()!!
        assertEquals(-500, live.imbalance)          // Ben has not cashed out yet

        repo.addLedger(s, ben, LedgerKind.CASH_OUT, 500)
        live = repo.liveSession(s).first()!!
        assertEquals(0, live.imbalance)
        assertEquals(500, live.standings.first { it.name == "Anna" }.total)
    }

    @Test
    fun anUnfinishedSessionIsFoundAgainAfterRestart() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val s = repo.startSession(gambioId(), listOf(anna))
        assertEquals(s, repo.openSession()?.id)

        repo.endSession(s)
        assertNull(repo.openSession())
    }

    @Test
    fun lastLineupPreselectsTheSamePlayers() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val g = gambioId()
        val s = repo.startSession(g, listOf(anna, ben))
        repo.endSession(s)

        assertEquals(listOf(anna, ben), repo.lastLineup(g))
    }

    @Test
    fun statsCountSessionsWonPerGame() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val g = gambioId()

        val s1 = repo.startSession(g, listOf(anna, ben))
        repo.recordScoreRound(s1, mapOf(anna to 1, ben to 9))   // Anna wins (low)
        repo.endSession(s1)

        val s2 = repo.startSession(g, listOf(anna, ben))
        repo.recordScoreRound(s2, mapOf(anna to 9, ben to 1))   // Ben wins
        repo.endSession(s2)

        // Counted inside the game's own card. There is no overall board any
        // more: a Skat point, a euro and a Durak loss are not the same kind of
        // thing, so nothing adds them up.
        val card = repo.stats().games.first { it.gameId == g }
        val records = card.records.associateBy { it.name }
        assertEquals(2, records["Anna"]!!.sessions)
        assertEquals(1, records["Anna"]!!.wins)
        assertEquals(1, records["Ben"]!!.wins)
    }

    @Test
    fun statsCountASessionThatHasNoRoundsAtAll() = runBlocking {
        // Start a game, end it without scoring: still a session that happened,
        // and everyone is tied on zero. It must not vanish from the stats.
        val anna = repo.addPlayer("Anna")
        val ben = repo.addPlayer("Ben")
        val s = repo.startSession(gambioId(), listOf(anna, ben))
        repo.endSession(s)

        val card = repo.stats().games.first { it.gameId == gambioId() }
        assertEquals(2, card.records.size)
        assertEquals(1, card.records.first { it.name == "Anna" }.sessions)
        // tied on zero, so both are winners
        assertEquals(1, card.records.first { it.name == "Anna" }.wins)
        assertEquals(1, card.records.first { it.name == "Ben" }.wins)
    }

    /**
     * The whole reason REMOVE is not a delete.
     *
     * Deleting the row would cascade through sessions, seats, rounds and
     * entries, so a mis-tap in Setup would take every night of that game with
     * it. Removing hides it; adding it back has to find the same row again,
     * because the unique index on gameKey means a second insert cannot even
     * succeed — it would either throw or, worse, be silently ignored and leave
     * the game unreachable forever with its history stranded behind it.
     */
    @Test
    fun removingAGameHidesItAndAddingItBackReturnsEveryNightItHad() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val gambio = repo.enabledGames().first().first { it.gameKey == "gambio" }
        val s = repo.startSession(gambio.id, listOf(anna))
        repo.recordScoreRound(s, mapOf(anna to 12))
        repo.endSession(s)

        repo.removeGame(gambio)
        assertNull(repo.enabledGames().first().firstOrNull { it.gameKey == "gambio" })
        // still on file, and its night with it
        assertNotNull(repo.allGames().first().firstOrNull { it.gameKey == "gambio" })
        assertEquals(1, repo.stats().games.count { it.gameId == gambio.id })

        repo.addFromCatalog(GameCatalog.all.first { it.key == "gambio" })
        val back = repo.enabledGames().first().first { it.gameKey == "gambio" }
        assertEquals(gambio.id, back.id)          // the same row, not a new one
        assertEquals(1, repo.stats().games.count { it.gameId == gambio.id })
    }

    @Test
    fun onboardingWritesExactlyWhatWasPickedAndCanBeHandedADuplicate() = runBlocking {
        val fresh = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, TrackerDb::class.java,
        ).build()
        val r = Repository(fresh)
        try {
            assertEquals(false, r.hasAnyGames())

            val picked = GameCatalog.all.filter { it.key in setOf("skat", "uno") } +
                GameCatalog.custom("Kniffel", Shape.PER_PLAYER_SCORE, glyph = "■")
            r.addAllFromCatalog(picked + picked)   // handed the same list twice

            val games = r.enabledGames().first()
            assertEquals(listOf("skat", "uno", "custom_kniffel"), games.map { it.gameKey })
            assertEquals("Kniffel", games.last().name)
            assertEquals("■", games.last().glyph)
            assertEquals(true, r.hasAnyGames())
        } finally {
            fresh.close()
        }
    }

    @Test
    fun exportContainsTheSessionAndItsRounds() = runBlocking {
        val anna = repo.addPlayer("Anna")
        val s = repo.startSession(gambioId(), listOf(anna))
        repo.recordScoreRound(s, mapOf(anna to 42))
        repo.endSession(s)

        val json = repo.exportJson()
        assert(json.contains("\"format\": 1"))
        assert(json.contains("Anna"))
        assert(json.contains("42"))
    }
}
