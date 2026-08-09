package eve.game.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import eve.game.tracker.domain.Direction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eve.game.tracker.domain.Shape

@Database(
    entities = [
        PlayerEntity::class,
        GameDefEntity::class,
        SessionEntity::class,
        SessionPlayerEntity::class,
        RoundEntity::class,
        RoundEntryEntity::class,
        LedgerEntryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TrackerDb : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun players(): PlayerDao
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: TrackerDb? = null

        /**
         * Drops the declarer columns with the mode that used them.
         *
         * Rebuild-and-copy rather than DROP COLUMN: the version of SQLite this
         * app is guaranteed on Android 8 has no ALTER TABLE DROP COLUMN, and
         * this is the pattern Room itself generates. Every round, seat, entry
         * and session survives — a Skat night recorded before the change keeps
         * its rounds, it just loses the three columns nothing reads any more.
         */
        internal val Migration1to2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // FIRST, before anything is dropped: convert every declarer
                // round into the per-player scores it always meant.
                //
                // A declarer round never had round_entries — its numbers were
                // *derived* from actorPlayerId/contractValue/won at read time.
                // Drop those columns without this step and every Skat night
                // ever recorded silently becomes a column of zeroes. So the
                // old scoring rules get run one last time, in SQL, and their
                // answers are written down as ordinary scores.
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO round_entries (roundId, playerId, value)
                    SELECT r.id, sp.playerId,
                      CASE WHEN sp.playerId = r.actorPlayerId THEN
                        CASE g.skatVariant
                          WHEN 'LIST' THEN
                            CASE WHEN r.won = 1 THEN r.contractValue ELSE -2 * r.contractValue END
                          WHEN 'BIERLACHS' THEN
                            CASE WHEN r.won = 1 THEN 0 ELSE -2 * r.contractValue END
                          ELSE
                            CASE WHEN r.won = 1 THEN r.contractValue + 50
                                 ELSE -2 * r.contractValue - 50 END
                        END
                      ELSE
                        CASE g.skatVariant
                          WHEN 'SEEGER_FABIAN' THEN CASE WHEN r.won = 1 THEN 0 ELSE 40 END
                          WHEN 'TURNIER' THEN
                            CASE WHEN r.won = 1 THEN 0 ELSE
                              CASE WHEN (SELECT COUNT(*) FROM session_players x
                                         WHERE x.sessionId = s.id) >= 4 THEN 30 ELSE 40 END
                            END
                          ELSE 0
                        END
                      END
                    FROM rounds r
                    JOIN sessions s ON s.id = r.sessionId
                    JOIN game_defs g ON g.id = s.gameDefId
                    JOIN session_players sp ON sp.sessionId = s.id
                    WHERE r.actorPlayerId IS NOT NULL
                      AND r.contractValue IS NOT NULL
                      AND r.won IS NOT NULL
                      AND sp.joinedAtRound <= r.roundIndex
                      AND (sp.leftAtRound IS NULL OR r.roundIndex < sp.leftAtRound)
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE TABLE rounds_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, roundIndex INTEGER NOT NULL, " +
                        "loserPlayerId INTEGER, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(sessionId) REFERENCES sessions(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO rounds_new (id, sessionId, roundIndex, loserPlayerId, createdAt) " +
                        "SELECT id, sessionId, roundIndex, loserPlayerId, createdAt FROM rounds"
                )
                db.execSQL("DROP TABLE rounds")
                db.execSQL("ALTER TABLE rounds_new RENAME TO rounds")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_rounds_sessionId_roundIndex " +
                        "ON rounds (sessionId, roundIndex)"
                )
                // Skat is a per-player score game now, like everything else
                // you write numbers down for.
                db.execSQL("ALTER TABLE game_defs RENAME TO game_defs_old")
                db.execSQL(
                    "CREATE TABLE game_defs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "gameKey TEXT NOT NULL, name TEXT NOT NULL, glyph TEXT NOT NULL, " +
                        "shape TEXT NOT NULL, direction TEXT NOT NULL, " +
                        "eliminationThreshold INTEGER, defaultBuyInCents INTEGER NOT NULL, " +
                        "currencySymbol TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                        "sortIndex INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO game_defs (id, gameKey, name, glyph, shape, direction, " +
                        "eliminationThreshold, defaultBuyInCents, currencySymbol, enabled, sortIndex) " +
                        "SELECT id, gameKey, name, glyph, " +
                        "CASE shape WHEN 'SINGLE_ACTOR' THEN 'PER_PLAYER_SCORE' ELSE shape END, " +
                        "direction, eliminationThreshold, defaultBuyInCents, currencySymbol, " +
                        "enabled, sortIndex FROM game_defs_old"
                )
                db.execSQL("DROP TABLE game_defs_old")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_game_defs_gameKey " +
                        "ON game_defs (gameKey)"
                )
            }
        }

        /**
         * Pins every session to the shape it was played under.
         *
         * Shape became a setting in this version (Durak can count losses or
         * points), and the two shapes store their rounds in different tables.
         * Existing nights were all played under their game's current shape, so
         * that is what gets written down — after this, changing the setting
         * cannot reach backwards.
         */
        internal val Migration2to3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN shape TEXT")
                db.execSQL(
                    "UPDATE sessions SET shape = " +
                        "(SELECT g.shape FROM game_defs g WHERE g.id = sessions.gameDefId)"
                )
            }
        }

        fun get(context: Context): TrackerDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, TrackerDb::class.java, "tracker.db",
            ).addMigrations(Migration1to2, Migration2to3).build().also { instance = it }
        }
    }
}

/**
 * The games that ship in v1 — the four named in idea.md, and deliberately only
 * those. Anything else your table plays is a row in `GameCatalog`.
 */
object DefaultGames {
    val list: List<GameDefEntity> = listOf(
        GameDefEntity(
            gameKey = "skat", name = "Skat", glyph = "♠",
            shape = Shape.PER_PLAYER_SCORE, direction = Direction.HIGHER_BETTER,
            sortIndex = 0,
        ),
        GameDefEntity(
            gameKey = "poker", name = "Poker", glyph = "♥",
            shape = Shape.LEDGER, defaultBuyInCents = 1000, currencySymbol = "€",
            sortIndex = 1,
        ),
        GameDefEntity(
            gameKey = "gambio", name = "Gambio", glyph = "♦",
            shape = Shape.PER_PLAYER_SCORE, direction = Direction.LOWER_BETTER,
            eliminationThreshold = null,
            sortIndex = 2,
        ),
        GameDefEntity(
            gameKey = "durak", name = "Durak", glyph = "♣",
            shape = Shape.LOSER_ONLY,
            sortIndex = 3,
        ),
        // Kept as a ranking, so the demo and the tests exercise the one shape
        // that stores a whole finishing order rather than a single name.
        GameDefEntity(
            gameKey = "uno", name = "Uno", glyph = "●",
            shape = Shape.RANKING,
            sortIndex = 4,
        ),
    )
}
