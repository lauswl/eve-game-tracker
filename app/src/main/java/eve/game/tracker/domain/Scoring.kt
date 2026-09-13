package eve.game.tracker.domain

/**
 * The shapes a round can have. Everything else in the app is a presentation
 * detail; this is the axis the games actually differ on.
 *
 *   PER_PLAYER_SCORE  every player gets a number every round  (Skat, Gambio)
 *   LOSER_ONLY        no score at all, just a loser per round (Durak)
 *   LEDGER            money in / money out, not rounds        (Poker)
 *   RANKING           the order everybody went out in         (Uno, Durak)
 *   WINNER_ONLY       one winner a round, one point           (Uno)
 *
 * The last two are not a second way of writing a number down — nothing is
 * typed. You tap names, and the app writes the points that finishing order
 * *is*: see [Scoring.rankingPoints]. They are stored as ordinary per-player
 * entries, so every total, chart and leaderboard downstream reads them without
 * knowing they were tapped rather than typed.
 *
 * There used to be a SINGLE_ACTOR, where you named the declarer, typed the game
 * value, and pressed WON or LOST while the app applied a scoring variant. It is
 * gone. It was a second way of writing a number down, it forced a choice of
 * variant nobody wants to make at a card table, and it could only ever score
 * the games whose rules it had been taught. You type the number and you type a
 * minus if they went off — which works for every game ever invented.
 */
enum class Shape { PER_PLAYER_SCORE, LOSER_ONLY, LEDGER, RANKING, WINNER_ONLY }

/** Which end of the scale wins. */
enum class Direction { LOWER_BETTER, HIGHER_BETTER }

/**
 * A game's rules. Flat fields rather than a JSON blob so the schema stays
 * inspectable and migrations stay boring.
 */
data class GameConfig(
    val shape: Shape,
    val direction: Direction = Direction.HIGHER_BETTER,
    /** Gambio-style knock-out. Null = no elimination. */
    val eliminationThreshold: Int? = null,
    /** Poker default buy-in, in minor units (cents). */
    val defaultBuyInCents: Int = 1000,
    val currencySymbol: String = "€",
)

/**
 * A player's participation in one session.
 *
 * [joinedAtRound] and [leftAtRound] are round indices (0-based, half-open:
 * a player is in for rounds `joinedAtRound until leftAtRound`). This is what
 * makes a mid-session join read as absence rather than a zero.
 */
data class Participant(
    val playerId: Long,
    val name: String,
    val seatIndex: Int,
    val joinedAtRound: Int = 0,
    val leftAtRound: Int? = null,
) {
    fun playedRound(index: Int): Boolean =
        index >= joinedAtRound && (leftAtRound == null || index < leftAtRound)
}

/**
 * One recorded round. Which fields are meaningful depends on the shape:
 *   PER_PLAYER_SCORE -> [entries]
 *   LOSER_ONLY       -> [loserId] (null means a drawn round)
 */
data class RoundData(
    val index: Int,
    val entries: Map<Long, Int> = emptyMap(),
    val loserId: Long? = null,
)

/** A poker buy-in or cash-out, in minor units. */
data class LedgerEntry(val playerId: Long, val kind: LedgerKind, val amountCents: Int)

enum class LedgerKind { BUY_IN, CASH_OUT }

/** One row of the standings. */
data class Standing(
    val playerId: Long,
    val name: String,
    /** Points, loss count, or net cents — whatever the shape counts. */
    val total: Int,
    /** How many rounds this player was actually in for. */
    val roundsPlayed: Int,
    /** 1 = best. Tied players share a rank, and the next rank is skipped. */
    val rank: Int,
    val eliminated: Boolean = false,
)

/**
 * Pure scoring. No Android, no database, no coroutines — every rule in the app
 * that can be got wrong lives here so it can be tested directly.
 */
object Scoring {

    /**
     * What belongs in one cell of the round table, or null if the player was
     * not in the game for that round (renders as "–", never as 0).
     */
    fun cellValue(config: GameConfig, round: RoundData, p: Participant): Int? {
        if (!p.playedRound(round.index)) return null
        return when (config.shape) {
            // Tapped or typed, a round is a number per player by the time it is
            // written down.
            Shape.PER_PLAYER_SCORE, Shape.RANKING, Shape.WINNER_ONLY ->
                round.entries[p.playerId]
            Shape.LOSER_ONLY -> when {
                round.loserId == null -> 0
                round.loserId == p.playerId -> 1
                else -> 0
            }
            Shape.LEDGER -> null
        }
    }

    /** Running total per player over the rounds they were present for. */
    fun totals(
        config: GameConfig,
        participants: List<Participant>,
        rounds: List<RoundData>,
    ): Map<Long, Int> = participants.associate { p ->
        p.playerId to rounds.sumOf { r -> cellValue(config, r, p) ?: 0 }
    }

    /** How many rounds each player was actually in for. */
    fun roundsPlayed(participants: List<Participant>, rounds: List<RoundData>): Map<Long, Int> =
        participants.associate { p -> p.playerId to rounds.count { p.playedRound(it.index) } }

    /**
     * Net position per player for a [Shape.LEDGER] game: cash-out minus the sum
     * of buy-ins, in minor units. Players with no entries still appear at 0.
     */
    fun ledgerNet(participants: List<Participant>, entries: List<LedgerEntry>): Map<Long, Int> =
        participants.associate { p ->
            val mine = entries.filter { it.playerId == p.playerId }
            val inn = mine.filter { it.kind == LedgerKind.BUY_IN }.sumOf { it.amountCents }
            val out = mine.filter { it.kind == LedgerKind.CASH_OUT }.sumOf { it.amountCents }
            p.playerId to (out - inn)
        }

    /**
     * A table only balances if every chip is accounted for. Non-zero means
     * someone miscounted, and catching that while everyone is still at the
     * table is the entire point of tracking it.
     */
    fun ledgerImbalance(participants: List<Participant>, entries: List<LedgerEntry>): Int =
        ledgerNet(participants, entries).values.sum()

    /**
     * The effective win direction. Most shapes carry it in config, but two are
     * fixed by their own logic: fewest losses always wins Durak, and a ledger is
     * always won by the biggest pile.
     */
    fun direction(config: GameConfig): Direction = when (config.shape) {
        Shape.LOSER_ONLY -> Direction.LOWER_BETTER
        Shape.LEDGER, Shape.RANKING, Shape.WINNER_ONLY -> Direction.HIGHER_BETTER
        Shape.PER_PLAYER_SCORE -> config.direction
    }

    /**
     * What a finishing order is worth, in points.
     *
     * Whoever is second to last scores 1, and every place further up the order
     * is worth one more — so with four players it is 3, 2, 1, 0 and with six it
     * is 5, 4, 3, 2, 1, 0. Last place scores nothing, because coming last is
     * not an achievement, and the winner scores exactly as much as the number
     * of people they beat.
     *
     * [order] is who went out first, second, third … Anyone missing from it did
     * not play that round and gets no entry at all, which reads as "–" in the
     * table rather than as a zero they did not earn.
     */
    fun rankingPoints(order: List<Long>): Map<Long, Int> =
        order.mapIndexed { i, id -> id to (order.size - 1 - i) }.toMap()

    /** One winner takes a point; everyone else at the table takes none. */
    fun winnerPoints(winnerId: Long?, playerIds: List<Long>): Map<Long, Int> =
        playerIds.associateWith { if (it == winnerId) 1 else 0 }

    /**
     * Big Two settlement used by this table.
     *
     * Penalty is based only on cards left:
     *  - 1..7   -> x1
     *  - 8..9   -> x2
     *  - 10..12 -> x3
     *  - 13     -> x5
     *
     * There are deliberately no extra multipliers for holding a 2 or for the
     * winner going out on a 2. Losers are negative; the winner gets the sum, so
     * every round is exactly zero-sum.
     */
    fun bigTwoPenalty(cardsLeft: Int): Int {
        require(cardsLeft in 0..13) { "cardsLeft must be between 0 and 13" }
        if (cardsLeft == 0) return 0
        val cardMultiplier = when (cardsLeft) {
            in 1..7 -> 1
            in 8..9 -> 2
            in 10..12 -> 3
            13 -> 5
            else -> error("unreachable")
        }
        return cardsLeft * cardMultiplier
    }

    fun bigTwoRoundScores(
        winnerId: Long,
        cardsLeft: Map<Long, Int>,
    ): Map<Long, Int> {
        require(winnerId in cardsLeft.keys) { "winner must be present in cardsLeft" }
        require(cardsLeft[winnerId] == 0) { "winner must have 0 cards left" }

        val result = cardsLeft.mapValues { (playerId, left) ->
            if (playerId == winnerId) 0 else -bigTwoPenalty(left)
        }.toMutableMap()
        result[winnerId] = -result.values.sum()
        return result
    }

    /**
     * Full standings, ranked. Ties share a rank and the following rank is
     * skipped (1, 2, 2, 4) — a tie is a tie and the app does not invent a
     * tiebreak it was not told about.
     */
    fun standings(
        config: GameConfig,
        participants: List<Participant>,
        rounds: List<RoundData> = emptyList(),
        ledger: List<LedgerEntry> = emptyList(),
    ): List<Standing> {
        val totals = if (config.shape == Shape.LEDGER) {
            ledgerNet(participants, ledger)
        } else {
            totals(config, participants, rounds)
        }
        val played = roundsPlayed(participants, rounds)
        val dir = direction(config)

        val ordered = participants.sortedWith(
            compareBy(
                { p -> if (dir == Direction.LOWER_BETTER) totals[p.playerId] ?: 0 else -(totals[p.playerId] ?: 0) },
                { it.seatIndex },
            )
        )

        var lastTotal: Int? = null
        var lastRank = 0
        return ordered.mapIndexed { i, p ->
            val total = totals[p.playerId] ?: 0
            val rank = if (total == lastTotal) lastRank else (i + 1).also { lastRank = it }
            lastTotal = total
            Standing(
                playerId = p.playerId,
                name = p.name,
                total = total,
                roundsPlayed = played[p.playerId] ?: 0,
                rank = rank,
                eliminated = isEliminated(config, total),
            )
        }
    }

    private fun isEliminated(config: GameConfig, total: Int): Boolean {
        val t = config.eliminationThreshold ?: return false
        return if (direction(config) == Direction.LOWER_BETTER) total >= t else total <= t
    }

    /**
     * What one player did across a run of rounds, counted per round rather than
     * per session.
     *
     * Sessions are the wrong denominator for half the games here. "Anna lost 19"
     * says nothing without knowing whether that was over four rounds or ninety,
     * and rounds per evening swing wildly — so the stats screen asks for rates
     * and needs the counts to build them from.
     *
     * [placeSum] is the sum of finishing places for a ranking game, so the mean
     * place is `placeSum / played`. Its own count is kept separately because a
     * round somebody sat out has no place to contribute.
     */
    data class RoundTally(
        val played: Int = 0,
        val won: Int = 0,
        val lost: Int = 0,
        val placeSum: Int = 0,
        val placed: Int = 0,
    )

    /**
     * Per-round counts for every participant.
     *
     * Only the shape's own facts are counted: a ranking round knows places, a
     * loser-only round knows losers, a winner-only round knows winners, and a
     * game where everybody writes a number down knows none of the three.
     */
    fun tally(
        config: GameConfig,
        participants: List<Participant>,
        rounds: List<RoundData>,
    ): Map<Long, RoundTally> = participants.associate { p ->
        var played = 0; var won = 0; var lost = 0; var placeSum = 0; var placed = 0
        for (r in rounds) {
            if (!p.playedRound(r.index)) continue
            played++
            when (config.shape) {
                Shape.LOSER_ONLY -> if (r.loserId == p.playerId) lost++
                Shape.WINNER_ONLY -> if ((r.entries[p.playerId] ?: 0) == 1) won++
                Shape.RANKING -> {
                    // A ranking round stores points, and points are the number
                    // of people you beat — so the place is however many were in
                    // the round, less that. Last place scores nothing and comes
                    // out as the highest place, which is what it is.
                    val points = r.entries[p.playerId] ?: continue
                    val place = r.entries.size - points
                    placeSum += place
                    placed++
                    if (place == 1) won++
                }
                else -> Unit
            }
        }
        p.playerId to RoundTally(played, won, lost, placeSum, placed)
    }

    /** Everyone on rank 1. Plural because a tie has no single winner. */
    fun leaders(standings: List<Standing>): List<Standing> = standings.filter { it.rank == 1 }

    /**
     * The best single round a player had, for the session summary. Null when the
     * shape has no per-round score worth boasting about.
     */
    fun bestRound(
        config: GameConfig,
        participant: Participant,
        rounds: List<RoundData>,
    ): Int? {
        // Nothing to boast about where the best possible round is the same
        // number for everyone who ever won one.
        if (config.shape != Shape.PER_PLAYER_SCORE) return null
        val values = rounds.mapNotNull { cellValue(config, it, participant) }
        if (values.isEmpty()) return null
        return if (direction(config) == Direction.LOWER_BETTER) values.min() else values.max()
    }
}
