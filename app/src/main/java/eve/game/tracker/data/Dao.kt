package eve.game.tracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM game_defs ORDER BY sortIndex, id")
    fun all(): Flow<List<GameDefEntity>>

    @Query("SELECT * FROM game_defs WHERE enabled = 1 ORDER BY sortIndex, id")
    fun enabled(): Flow<List<GameDefEntity>>

    @Query("SELECT * FROM game_defs WHERE id = :id")
    suspend fun byId(id: Long): GameDefEntity?

    @Query("SELECT COUNT(*) FROM game_defs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(defs: List<GameDefEntity>)

    @Update
    suspend fun update(def: GameDefEntity)

    @Query("DELETE FROM game_defs")
    suspend fun deleteAll()
}

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players WHERE retired = 0 ORDER BY isRegular DESC, displayName")
    fun active(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players ORDER BY displayName")
    fun all(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PlayerEntity>

    @Insert
    suspend fun insert(player: PlayerEntity): Long

    @Insert
    suspend fun insertAll(players: List<PlayerEntity>)

    @Update
    suspend fun update(player: PlayerEntity)

    @Delete
    suspend fun delete(player: PlayerEntity)

    @Query("DELETE FROM players")
    suspend fun deleteAll()
}

/** One row of [SessionDao.countsByGame]. */
data class GameSessionCount(val gameDefId: Long, val sessions: Int)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Insert
    suspend fun insertAllSessions(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun byIdFlow(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE gameDefId = :gameId ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastForGame(gameId: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY endedAt DESC")
    fun finished(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    fun lastFinished(): Flow<SessionEntity?>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    /**
     * Every session of one game, and by cascade its seats, rounds, entries and
     * ledger. This is what agreeing to the warning in Setup actually does.
     */
    @Query("DELETE FROM sessions WHERE gameDefId = :gameId")
    suspend fun deleteForGame(gameId: Long)

    // --- seats -----------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeats(seats: List<SessionPlayerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeat(seat: SessionPlayerEntity)

    @Update
    suspend fun updateSeat(seat: SessionPlayerEntity)

    @Query("SELECT * FROM session_players WHERE sessionId = :sessionId ORDER BY seatIndex")
    fun seats(sessionId: Long): Flow<List<SessionPlayerEntity>>

    @Query("SELECT * FROM session_players WHERE sessionId = :sessionId ORDER BY seatIndex")
    suspend fun seatsNow(sessionId: Long): List<SessionPlayerEntity>

    // --- rounds ----------------------------------------------------------
    @Insert
    suspend fun insertRound(round: RoundEntity): Long

    @Insert
    suspend fun insertAllRounds(rounds: List<RoundEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<RoundEntryEntity>)

    @Query("SELECT * FROM rounds WHERE sessionId = :sessionId ORDER BY roundIndex")
    fun rounds(sessionId: Long): Flow<List<RoundEntity>>

    @Query("SELECT * FROM rounds WHERE sessionId = :sessionId ORDER BY roundIndex")
    suspend fun roundsNow(sessionId: Long): List<RoundEntity>

    @Query(
        "SELECT e.* FROM round_entries e JOIN rounds r ON r.id = e.roundId " +
            "WHERE r.sessionId = :sessionId"
    )
    fun entries(sessionId: Long): Flow<List<RoundEntryEntity>>

    @Query("SELECT * FROM rounds WHERE sessionId = :sessionId ORDER BY roundIndex DESC LIMIT 1")
    suspend fun lastRound(sessionId: Long): RoundEntity?

    @Query("DELETE FROM rounds WHERE id = :roundId")
    suspend fun deleteRound(roundId: Long)

    @Query("UPDATE round_entries SET value = :value WHERE roundId = :roundId AND playerId = :playerId")
    suspend fun correctEntry(roundId: Long, playerId: Long, value: Int)

    @Query("UPDATE rounds SET loserPlayerId = :loser WHERE id = :roundId")
    suspend fun correctLoser(roundId: Long, loser: Long?)

    // --- ledger ----------------------------------------------------------
    @Insert
    suspend fun insertLedger(entry: LedgerEntryEntity): Long

    @Insert
    suspend fun insertAllLedger(entries: List<LedgerEntryEntity>)

    @Query("SELECT * FROM ledger_entries WHERE sessionId = :sessionId ORDER BY createdAt")
    fun ledger(sessionId: Long): Flow<List<LedgerEntryEntity>>

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteLedger(id: Long)

    @Query("SELECT * FROM ledger_entries WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastLedger(sessionId: Long): LedgerEntryEntity?

    /**
     * Records one round and its per-player scores together, so a crash can
     * never leave a round with half its entries.
     */
    @Transaction
    suspend fun recordRound(round: RoundEntity, entries: Map<Long, Int>): Long {
        val id = insertRound(round)
        if (entries.isNotEmpty()) {
            insertEntries(entries.map { (playerId, v) -> RoundEntryEntity(id, playerId, v) })
        }
        return id
    }

    /**
     * How many sessions each game has on file, so Setup can tell the difference
     * between a change that costs something and one that costs nothing.
     */
    @Query("SELECT gameDefId AS gameDefId, COUNT(*) AS sessions FROM sessions GROUP BY gameDefId")
    fun countsByGame(): Flow<List<GameSessionCount>>

    // --- export ----------------------------------------------------------
    @Query("SELECT * FROM sessions ORDER BY startedAt")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM session_players")
    suspend fun allSeats(): List<SessionPlayerEntity>

    @Query("SELECT * FROM rounds ORDER BY sessionId, roundIndex")
    suspend fun allRounds(): List<RoundEntity>

    @Query("SELECT * FROM round_entries")
    suspend fun allEntries(): List<RoundEntryEntity>

    @Query("SELECT * FROM ledger_entries")
    suspend fun allLedger(): List<LedgerEntryEntity>
}
