package eve.game.tracker

import eve.game.tracker.domain.Direction
import eve.game.tracker.domain.GameConfig
import eve.game.tracker.domain.LedgerEntry
import eve.game.tracker.domain.LedgerKind
import eve.game.tracker.domain.Participant
import eve.game.tracker.domain.RoundData
import eve.game.tracker.domain.Scoring
import eve.game.tracker.domain.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoring rules are where the real bugs live. A wrong Skat total is the
 * kind of bug that ends the app's career at the table, so every shape is
 * pinned here.
 */
class ScoringTest {

    private val anna = Participant(1, "Anna", 0)
    private val ben = Participant(2, "Ben", 1)
    private val cilli = Participant(3, "Cilli", 2)
    private val three = listOf(anna, ben, cilli)

    // ------------------------------------------------- PER_PLAYER_SCORE

    private val gambio = GameConfig(Shape.PER_PLAYER_SCORE, Direction.LOWER_BETTER)

    private fun gambioRounds() = listOf(
        RoundData(0, mapOf(1L to 12, 2L to 4, 3L to 21)),
        RoundData(1, mapOf(1L to 7, 2L to 19, 3L to 3)),
    )

    @Test
    fun `per player totals sum the rounds`() {
        val t = Scoring.totals(gambio, three, gambioRounds())
        assertEquals(19, t[1L])
        assertEquals(23, t[2L])
        assertEquals(24, t[3L])
    }

    @Test
    fun `lower better ranks the smallest total first`() {
        val s = Scoring.standings(gambio, three, gambioRounds())
        assertEquals("Anna", s.first().name)
        assertEquals(1, s.first().rank)
        assertEquals(listOf("Anna"), Scoring.leaders(s).map { it.name })
    }

    @Test
    fun `higher better flips the ranking`() {
        val high = gambio.copy(direction = Direction.HIGHER_BETTER)
        val s = Scoring.standings(high, three, gambioRounds())
        assertEquals("Cilli", s.first().name)
    }

    @Test
    fun `ties share a rank and the next rank is skipped`() {
        val rounds = listOf(RoundData(0, mapOf(1L to 10, 2L to 10, 3L to 20)))
        val s = Scoring.standings(gambio, three, rounds)
        assertEquals(listOf(1, 1, 3), s.map { it.rank })
        assertEquals(2, Scoring.leaders(s).size)
    }

    @Test
    fun `elimination threshold flags players who crossed it`() {
        val cfg = gambio.copy(eliminationThreshold = 20)
        val s = Scoring.standings(cfg, three, gambioRounds())
        assertFalse(s.first { it.name == "Anna" }.eliminated)   // 19
        assertTrue(s.first { it.name == "Cilli" }.eliminated)   // 24
    }

    // ---------------------------------------------- mid-session joining

    @Test
    fun `a player who joins late has no cell for the earlier rounds`() {
        val late = Participant(4, "Dora", 3, joinedAtRound = 1)
        val rounds = listOf(
            RoundData(0, mapOf(1L to 12)),
            RoundData(1, mapOf(1L to 7, 4L to 5)),
        )
        // absence, not a zero — this is the distinction the whole schema exists for
        assertNull(Scoring.cellValue(gambio, rounds[0], late))
        assertEquals(5, Scoring.cellValue(gambio, rounds[1], late))
    }

    @Test
    fun `a late joiner's total and rounds played cover only their own rounds`() {
        val late = Participant(4, "Dora", 3, joinedAtRound = 1)
        val rounds = listOf(
            RoundData(0, mapOf(1L to 12)),
            RoundData(1, mapOf(1L to 7, 4L to 5)),
        )
        val s = Scoring.standings(gambio, listOf(anna, late), rounds)
        assertEquals(5, s.first { it.name == "Dora" }.total)
        assertEquals(1, s.first { it.name == "Dora" }.roundsPlayed)
        assertEquals(2, s.first { it.name == "Anna" }.roundsPlayed)
    }

    @Test
    fun `a player who leaves keeps their history but scores no later rounds`() {
        val gone = Participant(2, "Ben", 1, leftAtRound = 1)
        val rounds = listOf(
            RoundData(0, mapOf(1L to 5, 2L to 3)),
            RoundData(1, mapOf(1L to 5, 2L to 99)),   // stray entry must be ignored
        )
        val s = Scoring.standings(gambio, listOf(anna, gone), rounds)
        assertEquals(3, s.first { it.name == "Ben" }.total)
        assertEquals(1, s.first { it.name == "Ben" }.roundsPlayed)
        assertNull(Scoring.cellValue(gambio, rounds[1], gone))
    }

    // ---------------------------------------------------- Skat, by hand

    @Test
    fun `skat is just numbers, and a minus is how you write going off`() {
        // There is no declarer mode any more. The scorer writes what each
        // player got: the declarer's points, or minus their points when they
        // went off, and nil for everyone else. Every Skat variant ever played
        // is expressible that way, which is more than the old mode managed.
        val skat = GameConfig(Shape.PER_PLAYER_SCORE, Direction.HIGHER_BETTER)
        val rounds = listOf(
            RoundData(0, mapOf(1L to 24, 2L to 0, 3L to 0)),    // Anna made 24
            RoundData(1, mapOf(1L to 0, 2L to -40, 3L to 0)),   // Ben went off
            RoundData(2, mapOf(1L to 0, 2L to 0, 3L to 27)),
        )
        val totals = Scoring.totals(skat, three, rounds)
        assertEquals(24, totals[1L])
        assertEquals(-40, totals[2L])
        assertEquals(27, totals[3L])
        assertEquals("Cilli", Scoring.leaders(Scoring.standings(skat, three, rounds)).single().name)
    }

    // -------------------------------------------------------- LOSER_ONLY

    @Test
    fun `durak counts losses and fewest wins`() {
        val cfg = GameConfig(Shape.LOSER_ONLY)
        val rounds = listOf(
            RoundData(0, loserId = 2),
            RoundData(1, loserId = 2),
            RoundData(2, loserId = 3),
        )
        val t = Scoring.totals(cfg, three, rounds)
        assertEquals(0, t[1L])
        assertEquals(2, t[2L])
        assertEquals(1, t[3L])
        assertEquals("Anna", Scoring.standings(cfg, three, rounds).first().name)
    }

    @Test
    fun `a drawn round costs nobody a loss`() {
        val cfg = GameConfig(Shape.LOSER_ONLY)
        val t = Scoring.totals(cfg, three, listOf(RoundData(0, loserId = null)))
        assertTrue(t.values.all { it == 0 })
    }

    @Test
    fun `direction is fixed for durak regardless of config`() {
        val cfg = GameConfig(Shape.LOSER_ONLY, direction = Direction.HIGHER_BETTER)
        assertEquals(Direction.LOWER_BETTER, Scoring.direction(cfg))
    }

    // ----------------------------------------------------------- RANKING

    private val ranked = GameConfig(Shape.RANKING)

    /**
     * The rule in the words it was given in: whoever comes second to last
     * scores one, and every place further up the order is worth one more. Which
     * makes last place worth nothing and a win worth exactly the number of
     * people you beat.
     */
    @Test
    fun `vorletzter scores one and every place above is worth one more`() {
        assertEquals(
            mapOf(1L to 3, 2L to 2, 3L to 1, 4L to 0),
            Scoring.rankingPoints(listOf(1, 2, 3, 4)),
        )
        assertEquals(mapOf(1L to 1, 2L to 0), Scoring.rankingPoints(listOf(1, 2)))
    }

    /** Six-handed, the winner beat five people and is worth five. */
    @Test
    fun `a win is worth more at a bigger table`() {
        val six = Scoring.rankingPoints(listOf(1, 2, 3, 4, 5, 6))
        assertEquals(5, six[1L])
        assertEquals(1, six[5L])
        assertEquals(0, six[6L])
    }

    @Test
    fun `ranking totals add up over the session and the most points wins`() {
        val rounds = listOf(
            RoundData(0, Scoring.rankingPoints(listOf(2, 1, 3))),   // Ben, Anna, Cilli
            RoundData(1, Scoring.rankingPoints(listOf(2, 3, 1))),   // Ben, Cilli, Anna
        )
        val t = Scoring.totals(ranked, three, rounds)
        assertEquals(1, t[1L])   // Anna: 1 + 0
        assertEquals(4, t[2L])   // Ben:  2 + 2
        assertEquals(1, t[3L])   // Cilli: 0 + 1
        assertEquals(Direction.HIGHER_BETTER, Scoring.direction(ranked))
        assertEquals("Ben", Scoring.leaders(Scoring.standings(ranked, three, rounds)).single().name)
    }

    /** Somebody who sat a round out gets no entry, which is not a zero. */
    @Test
    fun `a player left out of the order has no cell for that round`() {
        val round = RoundData(0, Scoring.rankingPoints(listOf(1, 2)))
        assertNull(Scoring.cellValue(ranked, round, cilli))
    }

    // ------------------------------------------------------- WINNER_ONLY

    private val winnerTakes = GameConfig(Shape.WINNER_ONLY)

    @Test
    fun `one winner takes a point and the rest take none`() {
        val rounds = listOf(
            RoundData(0, Scoring.winnerPoints(1, three.map { it.playerId })),
            RoundData(1, Scoring.winnerPoints(3, three.map { it.playerId })),
            RoundData(2, Scoring.winnerPoints(1, three.map { it.playerId })),
        )
        val t = Scoring.totals(winnerTakes, three, rounds)
        assertEquals(2, t[1L])
        assertEquals(0, t[2L])
        assertEquals(1, t[3L])
        assertEquals(Direction.HIGHER_BETTER, Scoring.direction(winnerTakes))
        assertEquals(
            "Anna",
            Scoring.leaders(Scoring.standings(winnerTakes, three, rounds)).single().name,
        )
    }

    /** Nothing to boast about where every win is worth the same number. */
    @Test
    fun `neither tapped shape claims a best round`() {
        val rounds = listOf(RoundData(0, Scoring.rankingPoints(listOf(1, 2, 3))))
        assertNull(Scoring.bestRound(ranked, anna, rounds))
        assertNull(Scoring.bestRound(winnerTakes, anna, rounds))
    }

    // ---------------------------------------------------------- BIG TWO

    @Test
    fun `big two base card multipliers match table rules`() {
        assertEquals(7, Scoring.bigTwoPenalty(7))
        assertEquals(16, Scoring.bigTwoPenalty(8))
        assertEquals(18, Scoring.bigTwoPenalty(9))
        assertEquals(30, Scoring.bigTwoPenalty(10))
        assertEquals(36, Scoring.bigTwoPenalty(12))
        assertEquals(65, Scoring.bigTwoPenalty(13))
    }

    @Test
    fun `big two unused twos stack and finish on two doubles again`() {
        assertEquals(260, Scoring.bigTwoPenalty(13, unusedTwos = 1, winnerFinishedOnTwo = true))
        assertEquals(520, Scoring.bigTwoPenalty(13, unusedTwos = 2, winnerFinishedOnTwo = true))
    }

    @Test
    fun `big two round is zero sum and winner receives every loser penalty`() {
        val scores = Scoring.bigTwoRoundScores(
            winnerId = 1,
            cardsLeft = mapOf(1L to 0, 2L to 8, 3L to 11, 4L to 6),
            unusedTwos = mapOf(3L to 1),
            winnerFinishedOnTwo = true,
        )
        // Ben: 8×2×2 = 32; Cilli: 11×3×2 unused-2×2 finish = 132;
        // Dora: 6×1×2 = 12. Anna receives 176.
        assertEquals(176, scores[1L])
        assertEquals(-32, scores[2L])
        assertEquals(-132, scores[3L])
        assertEquals(-12, scores[4L])
        assertEquals(0, scores.values.sum())
    }

    // ------------------------------------------------------------ LEDGER

    private val poker = GameConfig(Shape.LEDGER)

    @Test
    fun `net is cash out minus buy ins`() {
        val entries = listOf(
            LedgerEntry(1, LedgerKind.BUY_IN, 1000),
            LedgerEntry(1, LedgerKind.BUY_IN, 1000),
            LedgerEntry(1, LedgerKind.CASH_OUT, 3500),
            LedgerEntry(2, LedgerKind.BUY_IN, 1000),
            LedgerEntry(2, LedgerKind.CASH_OUT, 500),
        )
        val net = Scoring.ledgerNet(listOf(anna, ben), entries)
        assertEquals(1500, net[1L])
        assertEquals(-500, net[2L])
    }

    @Test
    fun `a balanced table sums to zero and an unbalanced one does not`() {
        val balanced = listOf(
            LedgerEntry(1, LedgerKind.BUY_IN, 1000),
            LedgerEntry(2, LedgerKind.BUY_IN, 1000),
            LedgerEntry(1, LedgerKind.CASH_OUT, 1500),
            LedgerEntry(2, LedgerKind.CASH_OUT, 500),
        )
        assertEquals(0, Scoring.ledgerImbalance(listOf(anna, ben), balanced))

        val short = balanced.dropLast(1)
        assertEquals(-500, Scoring.ledgerImbalance(listOf(anna, ben), short))
    }

    @Test
    fun `poker is won by the biggest pile and a player with no entries sits at zero`() {
        val entries = listOf(
            LedgerEntry(1, LedgerKind.BUY_IN, 1000),
            LedgerEntry(1, LedgerKind.CASH_OUT, 2000),
        )
        val s = Scoring.standings(poker, three, ledger = entries)
        assertEquals("Anna", s.first().name)
        assertEquals(1000, s.first().total)
        assertEquals(0, s.first { it.name == "Ben" }.total)
    }

    // ------------------------------------------------------- best round

    @Test
    fun `best round takes the smallest score when lower is better`() {
        val rounds = listOf(
            RoundData(0, mapOf(1L to 12)),
            RoundData(1, mapOf(1L to 3)),
            RoundData(2, mapOf(1L to 7)),
        )
        assertEquals(3, Scoring.bestRound(gambio, anna, rounds))
        assertEquals(12, Scoring.bestRound(gambio.copy(direction = Direction.HIGHER_BETTER), anna, rounds))
    }

    @Test
    fun `shapes without a per round score have no best round`() {
        assertNull(Scoring.bestRound(poker, anna, emptyList()))
        assertNull(Scoring.bestRound(GameConfig(Shape.LOSER_ONLY), anna, listOf(RoundData(0, loserId = 1))))
    }

    @Test
    fun `an empty session ranks everyone equal on zero`() {
        val s = Scoring.standings(gambio, three, emptyList())
        assertTrue(s.all { it.total == 0 && it.rank == 1 })
        assertEquals(3, Scoring.leaders(s).size)
    }
}
