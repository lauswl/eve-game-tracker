package eve.game.tracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import eve.game.tracker.domain.Direction
import eve.game.tracker.domain.LedgerKind
import eve.game.tracker.domain.Shape

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** A Stammspieler, offered first at player select. */
    val isRegular: Boolean = true,
    /** Hidden from player select but keeps every session they played. */
    val retired: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A game's definition. Rules are flat columns rather than a JSON blob, so the
 * schema stays inspectable and migrations stay boring.
 */
@Entity(tableName = "game_defs", indices = [Index(value = ["gameKey"], unique = true)])
data class GameDefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameKey: String,
    val name: String,
    val glyph: String,
    val shape: Shape,
    val direction: Direction = Direction.HIGHER_BETTER,
    val eliminationThreshold: Int? = null,
    val defaultBuyInCents: Int = 1000,
    val currencySymbol: String = "€",
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = GameDefEntity::class,
        parentColumns = ["id"], childColumns = ["gameDefId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("gameDefId")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameDefId: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    /**
     * The shape this night was actually played and recorded under, stamped when
     * the session starts.
     *
     * A game's shape is a setting now — Durak can be kept as fewest-losses or as
     * points — and the two record completely different rows: one writes a loser
     * per round, the other writes a number per player. Read a night back under
     * the *current* setting and every Durak evening you ever played turns into a
     * column of zeroes the moment somebody changes their mind. So the night
     * keeps its own shape and the setting only governs the next one.
     *
     * Null means "recorded before this column existed"; fall back to the game's
     * shape, which for those rows is still the one they were played under.
     */
    val shape: Shape? = null,
)

/**
 * A player's seat in one session.
 *
 * [joinedAtRound] / [leftAtRound] are half-open round indices. A player who
 * joins at round 12 has NO entries for rounds 0..11 — that renders as "–", not
 * as a zero, and their averages are over the rounds they actually played. This
 * has to be right in the schema from day one.
 */
@Entity(
    tableName = "session_players",
    primaryKeys = ["sessionId", "playerId"],
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PlayerEntity::class, ["id"], ["playerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId"), Index("playerId")],
)
data class SessionPlayerEntity(
    val sessionId: Long,
    val playerId: Long,
    val seatIndex: Int,
    val joinedAtRound: Int = 0,
    val leftAtRound: Int? = null,
)

@Entity(
    tableName = "rounds",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "roundIndex"], unique = true)],
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val roundIndex: Int,
    // LOSER_ONLY (Durak); null means a drawn round
    val loserPlayerId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One player's score in one round. Absent row = did not play that round. */
@Entity(
    tableName = "round_entries",
    primaryKeys = ["roundId", "playerId"],
    foreignKeys = [ForeignKey(RoundEntity::class, ["id"], ["roundId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("roundId")],
)
data class RoundEntryEntity(
    val roundId: Long,
    val playerId: Long,
    val value: Int,
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val playerId: Long,
    val kind: LedgerKind,
    val amountCents: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

class Converters {
    @TypeConverter fun shapeTo(v: Shape): String = v.name
    @TypeConverter fun shapeFrom(v: String): Shape = Shape.valueOf(v)

    @TypeConverter fun dirTo(v: Direction): String = v.name
    @TypeConverter fun dirFrom(v: String): Direction = Direction.valueOf(v)

    @TypeConverter fun ledgerTo(v: LedgerKind): String = v.name
    @TypeConverter fun ledgerFrom(v: String): LedgerKind = LedgerKind.valueOf(v)
}
