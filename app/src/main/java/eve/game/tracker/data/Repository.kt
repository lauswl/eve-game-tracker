package eve.game.tracker.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import eve.game.tracker.domain.Direction
import eve.game.tracker.domain.GameConfig
import eve.game.tracker.domain.LedgerEntry
import eve.game.tracker.domain.LedgerKind
import eve.game.tracker.domain.Participant
import eve.game.tracker.domain.RoundData
import eve.game.tracker.domain.Scoring
import eve.game.tracker.domain.Shape
import eve.game.tracker.domain.Standing

/**
 * The game's rules, optionally with the shape a particular night was played
 * under rather than the one the setting says today. See [SessionEntity.shape].
 */
fun GameDefEntity.toConfig(playedAs: Shape? = null) = GameConfig(
    shape = playedAs ?: shape,
    direction = direction,
    eliminationThreshold = eliminationThreshold,
    defaultBuyInCents = defaultBuyInCents,
    currencySymbol = currencySymbol,
)

/** Everything the scoring screen needs, already joined and mapped. */
data class LiveSession(
    val session: SessionEntity,
    val game: GameDefEntity,
    val participants: List<Participant>,
    val rounds: List<RoundData>,
    val ledger: List<LedgerEntry>,
) {
    val config: GameConfig get() = game.toConfig(session.shape)
    /** What this night is being scored as — not necessarily the setting today. */
    val shape: Shape get() = config.shape
    val standings: List<Standing>
        get() = Scoring.standings(config, participants, rounds, ledger)
    val roundCount: Int get() = rounds.size
    /** Non-zero means someone miscounted chips. Only meaningful for a ledger. */
    val imbalance: Int get() = Scoring.ledgerImbalance(participants, ledger)
}

class Repository(private val db: TrackerDb) {

    private val games = db.games()
    private val players = db.players()
    private val sessions = db.sessions()

    /**
     * The four games this app was built around.
     *
     * NOT called on a fresh install any more — onboarding asks which games you
     * play, and answering that question with a guess before anyone has been
     * asked is how you end up with a home screen you have to tidy before you can
     * use it. This is here for the demo seeder and for tests, which need a table
     * of games without a screen to pick them on.
     */
    suspend fun seedIfEmpty() {
        if (games.count() == 0) games.insertAll(DefaultGames.list)
    }

    /** True until somebody has chosen their games. Drives onboarding. */
    suspend fun hasAnyGames(): Boolean = games.count() > 0

    fun enabledGames(): Flow<List<GameDefEntity>> = games.enabled()
    fun allGames(): Flow<List<GameDefEntity>> = games.all()
    suspend fun updateGame(def: GameDefEntity) = games.update(def)

    /**
     * Adds a game at the end of the grid, or brings back one that was removed.
     *
     * Removing a game only unsets [GameDefEntity.enabled] — every night it was
     * ever played is still hanging off that row. So re-adding it has to find the
     * row rather than insert beside it, or the unique index on `gameKey` throws
     * and the game is gone for good with its history stranded behind it.
     *
     * Idempotent by key, which is also what makes the onboarding screen safe to
     * hand a list containing something already on file.
     */
    suspend fun addFromCatalog(entry: CatalogEntry) {
        val existing = games.all().first()
        val prior = existing.firstOrNull { it.gameKey == entry.key }
        if (prior != null) {
            if (!prior.enabled) games.update(prior.copy(enabled = true))
            return
        }
        games.insertAll(listOf(entry.toEntity((existing.maxOfOrNull { it.sortIndex } ?: -1) + 1)))
    }

    suspend fun addAllFromCatalog(entries: List<CatalogEntry>) =
        entries.forEach { addFromCatalog(it) }

    /**
     * Takes a game off the home screen without touching a single round.
     *
     * This is what the REMOVE button does, and why it is not a delete. Deleting
     * the row would cascade through sessions, seats, rounds and entries and take
     * every night of Skat you ever played with it. Re-adding it from the library
     * puts it back exactly as it was.
     */
    suspend fun removeGame(def: GameDefEntity) = games.update(def.copy(enabled = false))
    suspend fun gameById(id: Long) = games.byId(id)

    /**
     * Changes how a game is scored, and throws away everything scored the old
     * way — which is the only honest thing to do with it.
     *
     * Least-losses writes a loser per round; ranking writes a points row per
     * player; low-wins and high-wins put the same numbers in opposite order.
     * Rows recorded under one of those do not mean anything under another, so
     * they are not carried forward and they are not silently reinterpreted.
     * Setup asks first, in red, naming the game.
     */
    suspend fun changeScoring(def: GameDefEntity) {
        sessions.deleteForGame(def.id)
        games.update(def)
    }

    /**
     * The whole database, gone: games, players, every session ever recorded.
     * Not a worker-thread call from the UI — Room's own annotation says so, and
     * it will throw on the main thread rather than quietly block it.
     */
    suspend fun deleteEverything() = withContext(Dispatchers.IO) { db.clearAllTables() }

    fun activePlayers(): Flow<List<PlayerEntity>> = players.active()
    suspend fun addPlayer(name: String, regular: Boolean = true): Long =
        players.insert(PlayerEntity(displayName = name.trim(), isRegular = regular))
    suspend fun updatePlayer(p: PlayerEntity) = players.update(p)

    // ---------------------------------------------------------------- sessions

    suspend fun startSession(gameDefId: Long, playerIds: List<Long>): Long {
        // Stamped now, so changing the game's shape later cannot rewrite what
        // this night meant.
        val shape = games.byId(gameDefId)?.shape
        val id = sessions.insert(SessionEntity(gameDefId = gameDefId, shape = shape))
        sessions.insertSeats(
            playerIds.mapIndexed { i, pid ->
                SessionPlayerEntity(sessionId = id, playerId = pid, seatIndex = i)
            }
        )
        return id
    }

    suspend fun openSession(): SessionEntity? = sessions.openSession()

    /**
     * Who played this game last time. Pre-selecting them is what keeps the
     * promise of two taps from the home screen to a live table.
     */
    suspend fun lastLineup(gameId: Long): List<Long> {
        val last = sessions.lastForGame(gameId) ?: return emptyList()
        return sessions.seatsNow(last.id).sortedBy { it.seatIndex }.map { it.playerId }
    }

    suspend fun endSession(sessionId: Long) {
        val s = sessions.byId(sessionId) ?: return
        sessions.update(s.copy(endedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(sessionId: Long) = sessions.deleteById(sessionId)

    /**
     * A player joining mid-session starts at the CURRENT round, so the rounds
     * before it have no entries for them at all — that reads as "–", not zero.
     */
    suspend fun addPlayerToSession(sessionId: Long, playerId: Long) {
        val seats = sessions.seatsNow(sessionId)
        if (seats.any { it.playerId == playerId }) return
        val roundNow = sessions.roundsNow(sessionId).size
        sessions.insertSeat(
            SessionPlayerEntity(
                sessionId = sessionId,
                playerId = playerId,
                seatIndex = (seats.maxOfOrNull { it.seatIndex } ?: -1) + 1,
                joinedAtRound = roundNow,
            )
        )
    }

    /** Marks a player as having left. Their history stays exactly as it was. */
    suspend fun removePlayerFromSession(sessionId: Long, playerId: Long) {
        val seat = sessions.seatsNow(sessionId).firstOrNull { it.playerId == playerId } ?: return
        val roundNow = sessions.roundsNow(sessionId).size
        sessions.updateSeat(seat.copy(leftAtRound = roundNow))
    }

    fun liveSession(sessionId: Long): Flow<LiveSession?> =
        combine(
            sessions.byIdFlow(sessionId),
            sessions.seats(sessionId),
            sessions.rounds(sessionId),
            sessions.entries(sessionId),
            sessions.ledger(sessionId),
        ) { session, seats, rounds, entries, ledger ->
            if (session == null) return@combine null
            val game = games.byId(session.gameDefId) ?: return@combine null
            val names = players.byIds(seats.map { it.playerId }).associateBy { it.id }
            val byRound = entries.groupBy { it.roundId }
            LiveSession(
                session = session,
                game = game,
                participants = seats.map { s ->
                    Participant(
                        playerId = s.playerId,
                        name = names[s.playerId]?.displayName ?: "?",
                        seatIndex = s.seatIndex,
                        joinedAtRound = s.joinedAtRound,
                        leftAtRound = s.leftAtRound,
                    )
                },
                rounds = rounds.map { r ->
                    RoundData(
                        index = r.roundIndex,
                        entries = byRound[r.id].orEmpty().associate { it.playerId to it.value },
                        loserId = r.loserPlayerId,
                    )
                },
                ledger = ledger.map { LedgerEntry(it.playerId, it.kind, it.amountCents) },
            )
        }

    // ------------------------------------------------------------------ rounds

    suspend fun recordScoreRound(sessionId: Long, scores: Map<Long, Int>) {
        val index = sessions.roundsNow(sessionId).size
        sessions.recordRound(RoundEntity(sessionId = sessionId, roundIndex = index), scores)
    }

    suspend fun recordLoserRound(sessionId: Long, loserId: Long?) {
        val index = sessions.roundsNow(sessionId).size
        sessions.recordRound(
            RoundEntity(sessionId = sessionId, roundIndex = index, loserPlayerId = loserId),
            emptyMap(),
        )
    }

    /** Undo drops the whole last round. Correcting a cell edits it in place. */
    suspend fun undoLastRound(sessionId: Long) {
        sessions.lastRound(sessionId)?.let { sessions.deleteRound(it.id) }
    }

    suspend fun correctScore(sessionId: Long, roundIndex: Int, playerId: Long, value: Int) {
        val round = sessions.roundsNow(sessionId).firstOrNull { it.roundIndex == roundIndex } ?: return
        sessions.correctEntry(round.id, playerId, value)
    }

    suspend fun correctLoser(sessionId: Long, roundIndex: Int, loserId: Long?) {
        val round = sessions.roundsNow(sessionId).firstOrNull { it.roundIndex == roundIndex } ?: return
        sessions.correctLoser(round.id, loserId)
    }

    // ------------------------------------------------------------------ ledger

    suspend fun addLedger(sessionId: Long, playerId: Long, kind: LedgerKind, cents: Int) {
        sessions.insertLedger(
            LedgerEntryEntity(
                sessionId = sessionId, playerId = playerId, kind = kind, amountCents = cents,
            )
        )
    }

    suspend fun undoLastLedger(sessionId: Long) {
        sessions.lastLedger(sessionId)?.let { sessions.deleteLedger(it.id) }
    }

    /**
     * How many sessions each game has, live.
     *
     * Setup asks so it can keep quiet: a warning about losing data, shown to
     * somebody with no data, teaches them that the warnings here are noise —
     * and the one that matters looks exactly the same.
     */
    fun sessionCountsByGame(): Flow<Map<Long, Int>> =
        sessions.countsByGame().map { rows -> rows.associate { it.gameDefId to it.sessions } }

    /** True when DELETE ALL DATA would actually take something away. */
    fun hasStoredData(): Flow<Boolean> =
        combine(sessionCountsByGame(), players.all()) { counts, people ->
            counts.isNotEmpty() || people.isNotEmpty()
        }

    // ------------------------------------------------------------------- stats

    fun lastFinishedSummary(): Flow<LiveSession?> =
        sessions.lastFinished().map { it?.id }.flatMapLatestCompat { id ->
            if (id == null) flowOf(null) else liveSession(id)
        }

    /** Recomputes whenever a session finishes — the only event that moves it. */
    fun statsFlow(): Flow<StatsSnapshot> = sessions.finished().map { stats() }

    /**
     * Leaderboards, computed in Kotlin off the same tested engine rather than in
     * SQL. Scoring is shape-dependent, so a SQL version would have to
     * reimplement four rule sets in a second language and could drift from the
     * one that is under test.
     */
    suspend fun stats(): StatsSnapshot {
        val gameDefs = games.all().first().associateBy { it.id }
        val seats = sessions.allSeats().groupBy { it.sessionId }
        val rounds = sessions.allRounds().groupBy { it.sessionId }
        val entriesByRound = sessions.allEntries().groupBy { it.roundId }
        val ledger = sessions.allLedger().groupBy { it.sessionId }
        val names = players.all().first().associate { it.id to it.displayName }

        // Oldest first: everything downstream — series, streaks, form — is a
        // walk in chronological order, so sort once here and never again.
        val finished = sessions.allSessions()
            .filter { it.endedAt != null }
            .sortedBy { it.endedAt }

        val played = mutableListOf<PlayedSession>()
        for (s in finished) {
            val game = gameDefs[s.gameDefId] ?: continue
            val mySeats = seats[s.id].orEmpty()
            if (mySeats.isEmpty()) continue
            val participants = mySeats.map {
                Participant(it.playerId, names[it.playerId] ?: "?", it.seatIndex, it.joinedAtRound, it.leftAtRound)
            }
            val myRounds = rounds[s.id].orEmpty().sortedBy { it.roundIndex }.map { r ->
                RoundData(
                    index = r.roundIndex,
                    entries = entriesByRound[r.id].orEmpty().associate { it.playerId to it.value },
                    loserId = r.loserPlayerId,
                )
            }
            val myLedger = ledger[s.id].orEmpty().map { LedgerEntry(it.playerId, it.kind, it.amountCents) }
            val config = game.toConfig(s.shape)
            val standings = Scoring.standings(config, participants, myRounds, myLedger)
            if (standings.isEmpty()) continue
            played += PlayedSession(
                s, game, config.shape, standings, myRounds.size,
                tally = Scoring.tally(config, participants, myRounds),
            )
        }

        // Grouped by game AND by the shape it was played under. A Durak switched
        // from fewest-losses to points is two different things being counted,
        // and one chart adding loss counts to point totals would be a line that
        // means nothing. Two cards, each labelled with what it counts.
        val byGameAndShape = played.groupBy { it.game.id to it.shape }
        val split = byGameAndShape.keys.groupBy { it.first }.filterValues { it.size > 1 }.keys
        val gameStats = byGameAndShape
            .map { (key, list) -> buildGameStats(list, qualified = key.first in split) }
            .sortedByDescending { it.sessions }

        return StatsSnapshot(
            games = gameStats,
            totalSessions = played.size,
            totalRounds = played.sumOf { it.roundCount },
            firstSessionAt = played.firstOrNull()?.session?.startedAt,
        )
    }

    /** One game's leaderboard plus the running total each player is on. */
    private fun buildGameStats(list: List<PlayedSession>, qualified: Boolean = false): GameStats {
        val game = list.first().game
        val shape = list.first().shape
        val order = list.flatMap { it.standings }.map { it.playerId }.distinct()
        val running = order.associateWith { 0 }.toMutableMap()
        val series = order.associateWith { mutableListOf<Int>() }

        for (p in list) {
            val byId = p.standings.associateBy { it.playerId }
            for (id in order) {
                // A player who sat this one out holds their line flat rather
                // than dropping to zero — absence is not a result.
                running[id] = running.getValue(id) + (byId[id]?.total ?: 0)
                series.getValue(id) += running.getValue(id)
            }
        }

        val unit = when (shape) {
            Shape.LEDGER -> StatUnit.MONEY
            Shape.LOSER_ONLY -> StatUnit.LOSSES
            else -> StatUnit.POINTS
        }
        // Off the shape it was PLAYED under, not the game's column today: the
        // two only differ for sessions recorded before a mode change, and a
        // card labelled with the wrong unit is worse than no card.
        val kind = statKindFor(game.copy(shape = shape))
        val lower = Scoring.direction(game.toConfig(shape)) == Direction.LOWER_BETTER
        val rowOrder = compareBy<PlayerRecord> { r ->
            // Best first, whichever end of the scale "best" is at for this card.
            val v = r.headline(kind)
            if (bestIsLowest(kind, lower)) v else -v
        }.thenByDescending { it.rounds }.thenBy { it.name }
        return GameStats(
            gameId = game.id,
            // Only ever qualified when the same game really was played both
            // ways — otherwise "Durak" is just Durak.
            name = if (qualified) "${game.name} · ${unit.label}" else game.name,
            glyph = game.glyph,
            shape = shape,
            unit = unit,
            kind = kind,
            currencySymbol = game.currencySymbol,
            // Straight from the scoring engine rather than off the game's
            // direction column: fewest losses wins Durak whatever that column
            // happens to say, and the stats screen was reading it the wrong way
            // round — the player with the most losses got the winner's disc.
            lowerIsBetter = lower,
            sessions = list.size,
            rounds = list.sumOf { it.roundCount },
            records = buildRecords(list).sortedWith(rowOrder),
            series = order.map { id ->
                PlayerSeries(
                    playerId = id,
                    name = list.firstNotNullOf { p -> p.standings.firstOrNull { it.playerId == id } }.name,
                    cumulative = series.getValue(id),
                )
            },
        )
    }

    /**
     * Sessions, wins, streaks, per-session results and per-round counts for
     * every player in the given run of sessions. Chronological, because a streak
     * is not a count.
     */
    private fun buildRecords(played: List<PlayedSession>): List<PlayerRecord> {
        data class Acc(
            var name: String, var sessions: Int = 0, var wins: Int = 0,
            var net: Int = 0, var streak: Int = 0, var best: Int = 0,
            var rounds: Int = 0, var roundsWon: Int = 0, var roundsLost: Int = 0,
            var placeSum: Int = 0, var placed: Int = 0,
            val results: MutableList<Int> = mutableListOf(),
        )

        val acc = LinkedHashMap<Long, Acc>()
        for (p in played) {
            for (st in p.standings) {
                val a = acc.getOrPut(st.playerId) { Acc(st.name) }
                a.name = st.name
                a.sessions++
                a.net += st.total
                a.results += st.total
                p.tally[st.playerId]?.let { t ->
                    a.rounds += t.played
                    a.roundsWon += t.won
                    a.roundsLost += t.lost
                    a.placeSum += t.placeSum
                    a.placed += t.placed
                }
                if (st.rank == 1) {
                    a.wins++
                    a.streak++
                    if (a.streak > a.best) a.best = a.streak
                } else {
                    a.streak = 0
                }
            }
        }
        return acc.map { (id, a) ->
            PlayerRecord(
                playerId = id, name = a.name, sessions = a.sessions, wins = a.wins,
                net = a.net, currentStreak = a.streak, bestStreak = a.best,
                results = a.results, rounds = a.rounds, roundsWon = a.roundsWon,
                roundsLost = a.roundsLost, placeSum = a.placeSum, placed = a.placed,
            )
        }
    }

    // -------------------------------------------------------------------- demo

    /**
     * A season of plausible game nights, for looking at the Stats screen while
     * it is being designed.
     *
     * Judging a leaderboard against one session and two players called "V" and
     * "B" is judging an empty screen — no chart draws, no streak exists, and
     * every layout looks fine because nothing is in it. Deterministic on
     * purpose: the same history every time means two screenshots differ only by
     * the thing being compared.
     *
     * Debug affordance, wired to the same launch flag as the layout switches,
     * and it leaves with them.
     */
    suspend fun seedDemoData() {
        if (sessions.allSessions().isNotEmpty()) return
        seedIfEmpty()
        val defs = games.all().first().associateBy { it.gameKey }
        val people = listOf("Anna", "Ben", "Chris", "Dora").map { addPlayer(it) }
        val (anna, ben, chris, dora) = people

        // A pseudo-random but fixed sequence: no Random, so the screenshots
        // taken an hour apart are still comparable.
        var seed = 20260805
        fun next(bound: Int): Int {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return seed % bound
        }

        suspend fun night(key: String, lineup: List<Long>, rounds: Int, body: suspend (Long) -> Unit) {
            val def = defs[key] ?: return
            val id = startSession(def.id, lineup)
            repeat(rounds) { body(id) }
            endSession(id)
        }

        repeat(7) { n ->
            night("skat", listOf(anna, ben, chris), 8) { id ->
                // one declarer scores, the other two take nothing — written
                // by hand now, as at a real table
                val actor = next(3)
                val value = listOf(18, 20, 24, 27, 33, 36, 48)[next(7)]
                val got = if (next(10) < 7) value else -2 * value
                recordScoreRound(
                    id,
                    listOf(anna, ben, chris).mapIndexed { i, p -> p to if (i == actor) got else 0 }.toMap(),
                )
            }
            if (n % 2 == 0) night("gambio", listOf(anna, ben, chris, dora), 6) { id ->
                recordScoreRound(
                    id,
                    listOf(anna, ben, chris, dora).associateWith { next(14) },
                )
            }
            if (n % 3 == 0) night("durak", listOf(anna, ben, chris, dora), 9) { id ->
                recordLoserRound(id, listOf(anna, ben, chris, dora)[next(4)])
            }
            // Shuffled by walking a Fisher-Yates with the same fixed sequence,
            // so the finishing order is plausible and still the same every run.
            if (n % 2 == 0) night("uno", listOf(anna, ben, chris, dora), 11) { id ->
                val order = mutableListOf(anna, ben, chris, dora)
                for (i in order.lastIndex downTo 1) {
                    val j = next(i + 1)
                    val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                }
                recordScoreRound(id, Scoring.rankingPoints(order))
            }
            if (n % 2 == 1) night("poker", listOf(anna, ben, chris), 0) { }
                .also {
                    val s = sessions.allSessions().last()
                    listOf(anna, ben, chris).forEach { p ->
                        addLedger(s.id, p, LedgerKind.BUY_IN, 1000 * (1 + next(2)))
                    }
                    val pot = sessions.allLedger().filter { it.sessionId == s.id }
                        .sumOf { it.amountCents }
                    var left = pot
                    listOf(anna, ben).forEach { p ->
                        val take = next(left / 2 + 1)
                        addLedger(s.id, p, LedgerKind.CASH_OUT, take)
                        left -= take
                    }
                    addLedger(s.id, chris, LedgerKind.CASH_OUT, left)
                }
        }
    }

    // ------------------------------------------------------------------ export

    suspend fun exportJson(): String = Exporter.toJson(
        games = games.all().first(),
        players = players.all().first(),
        sessions = sessions.allSessions(),
        seats = sessions.allSeats(),
        rounds = sessions.allRounds(),
        entries = sessions.allEntries(),
        ledger = sessions.allLedger(),
    )

    /**
     * Replaces the database with an export. Parsing and validation happen
     * before this transaction starts; a bad file cannot clear working data.
     */
    suspend fun importJson(json: String) = withContext(Dispatchers.IO) {
        val imported = Importer.fromJson(json)
        db.withTransaction {
            // Explicit order keeps foreign-key handling obvious. Session rows
            // cascade to seats, rounds, scores and ledger entries.
            sessions.deleteAllSessions()
            games.deleteAll()
            players.deleteAll()

            if (imported.games.isNotEmpty()) games.insertAll(imported.games)
            if (imported.players.isNotEmpty()) players.insertAll(imported.players)
            if (imported.sessions.isNotEmpty()) sessions.insertAllSessions(imported.sessions)
            if (imported.seats.isNotEmpty()) sessions.insertSeats(imported.seats)
            if (imported.rounds.isNotEmpty()) sessions.insertAllRounds(imported.rounds)
            if (imported.entries.isNotEmpty()) sessions.insertEntries(imported.entries)
            if (imported.ledger.isNotEmpty()) sessions.insertAllLedger(imported.ledger)
        }
    }
}

/** One finished session with its result already computed. */
private data class PlayedSession(
    val session: SessionEntity,
    val game: GameDefEntity,
    /** As played, which is not necessarily how the game is set up today. */
    val shape: Shape,
    val standings: List<Standing>,
    val roundCount: Int,
    /** Per-round counts, for the cards that are about rates rather than totals. */
    val tally: Map<Long, Scoring.RoundTally> = emptyMap(),
)

/** What a game's numbers actually are, so the UI can format and not guess. */
enum class StatUnit(val label: String) {
    POINTS("points"), MONEY("money"), LOSSES("losses")
}

/**
 * The one number a game is judged on, and therefore which card it gets.
 *
 * There is exactly one per game, because how a game is scored is decided once
 * when it is added and never drifts afterwards. So a card never has to ask
 * which mode it is in — the card *is* the game.
 *
 * The unit is chosen to be the one that survives a season honestly:
 *
 *  - [POINTS] per **session**, not a running total. A total is a leaderboard of
 *    attendance: turn up twice as often and you win it without playing better.
 *  - [MONEY] as a total, because it is the one unit where the total is a real
 *    thing that happened to somebody's wallet.
 *  - [LOSS_RATE] and [WIN_RATE] per **round**, because rounds per evening swing
 *    from four to forty and a count of either is mostly a count of rounds.
 *  - [PLACE] as a mean, because ranking points scale with head count — six
 *    players inflate everyone's score and say nothing about who played better.
 *  - [CARDS] per round, for the same reason as the rates.
 */
enum class StatKind(val label: String) {
    POINTS("Points per session"),
    MONEY("Net"),
    LOSS_RATE("Rounds lost"),
    WIN_RATE("Rounds won"),
    PLACE("Average place"),
    CARDS("Cards left"),
}

/**
 * Which card a game gets. Read off the mode it was added under where the
 * catalogue offers one — Uno kept as "least cards" counts cards, and no other
 * low-wins game does — and off the shape otherwise.
 */
fun statKindFor(game: GameDefEntity): StatKind {
    val entry = GameCatalog.all.firstOrNull { it.key == game.gameKey }
    val mode = entry?.modes?.firstOrNull {
        it.shape == game.shape &&
            (it.shape != Shape.PER_PLAYER_SCORE || it.direction == game.direction)
    }
    if (mode?.label == "Least cards") return StatKind.CARDS
    return when (game.shape) {
        Shape.LEDGER -> StatKind.MONEY
        Shape.LOSER_ONLY -> StatKind.LOSS_RATE
        Shape.WINNER_ONLY -> StatKind.WIN_RATE
        Shape.RANKING -> StatKind.PLACE
        Shape.PER_PLAYER_SCORE -> StatKind.POINTS
    }
}

/**
 * A player's record in one game. [net] and [results] are in the units of the
 * game they came from, so they are only ever shown inside that game's card —
 * Skat points and poker euros do not add up, and a composite "overall score"
 * would be a number the app invented.
 */
data class PlayerRecord(
    val name: String,
    val sessions: Int,
    val wins: Int,
    val playerId: Long = 0L,
    val net: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val results: List<Int> = emptyList(),
    /** Rounds this player was actually in for, across every session. */
    val rounds: Int = 0,
    val roundsWon: Int = 0,
    val roundsLost: Int = 0,
    val placeSum: Int = 0,
    val placed: Int = 0,
) {
    val winRate: Double get() = if (sessions == 0) 0.0 else wins.toDouble() / sessions
    val lossRate: Double get() = if (rounds == 0) 0.0 else roundsLost.toDouble() / rounds
    val roundWinRate: Double get() = if (rounds == 0) 0.0 else roundsWon.toDouble() / rounds
    val averagePlace: Double get() = if (placed == 0) 0.0 else placeSum.toDouble() / placed
    val perSession: Double get() = if (sessions == 0) 0.0 else net.toDouble() / sessions
    val perRound: Double get() = if (rounds == 0) 0.0 else net.toDouble() / rounds
    val best: Int? get() = results.maxOrNull()
    val worst: Int? get() = results.minOrNull()
    val average: Double get() = if (results.isEmpty()) 0.0 else results.average()

    /** The number this player's row is sorted and headlined by. */
    fun headline(kind: StatKind): Double = when (kind) {
        StatKind.POINTS -> perSession
        StatKind.MONEY -> net.toDouble()
        StatKind.LOSS_RATE -> lossRate
        StatKind.WIN_RATE -> roundWinRate
        StatKind.PLACE -> averagePlace
        StatKind.CARDS -> perRound
    }
}

/**
 * Which end of this card's scale wins.
 *
 * Not the same question as the game's direction: a low Gambio score is good and
 * a low average place is good, but so is a *high* rounds-won rate in a game
 * whose direction column says nothing useful at all.
 */
fun bestIsLowest(kind: StatKind, lowerIsBetter: Boolean): Boolean = when (kind) {
    StatKind.POINTS -> lowerIsBetter
    StatKind.MONEY, StatKind.WIN_RATE -> false
    StatKind.LOSS_RATE, StatKind.PLACE, StatKind.CARDS -> true
}

/** A player's running total after each session of one game, oldest first. */
data class PlayerSeries(
    val playerId: Long,
    val name: String,
    val cumulative: List<Int>,
)

data class GameStats(
    val gameId: Long,
    val name: String,
    val glyph: String,
    val shape: Shape,
    val unit: StatUnit,
    val kind: StatKind,
    val currencySymbol: String,
    val lowerIsBetter: Boolean,
    val sessions: Int,
    val rounds: Int,
    val records: List<PlayerRecord>,
    val series: List<PlayerSeries>,
) {
    /** A running line only means something where the total does. */
    val plots: Boolean get() = kind == StatKind.POINTS || kind == StatKind.MONEY
}

/**
 * Everything the Stats tab draws — which is one card per game and nothing else.
 *
 * There is no overall board and no head-to-head grid any more. Both added
 * numbers from different games together: a Skat point, a euro and a Durak loss
 * are not the same kind of thing, and "sessions won across every game" quietly
 * rewards whoever plays the game with the fewest people at the table.
 */
data class StatsSnapshot(
    val games: List<GameStats> = emptyList(),
    val totalSessions: Int = 0,
    val totalRounds: Int = 0,
    val firstSessionAt: Long? = null,
)

/** flatMapLatest without pulling in the experimental opt-in at every call site. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun <T, R> Flow<T>.flatMapLatestCompat(transform: suspend (T) -> Flow<R>): Flow<R> =
    flatMapLatest(transform)
