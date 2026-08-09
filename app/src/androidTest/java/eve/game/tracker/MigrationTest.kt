package eve.game.tracker

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eve.game.tracker.data.TrackerDb
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Version 1 to 2: the declarer mode leaves and takes three columns with it.
 *
 * This is the one change in the app that can destroy data that already exists,
 * so it is the one change that gets tested against a real version-1 database
 * rather than against the code's opinion of one. Anyone who played a Skat night
 * before the change keeps every round of it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TrackerDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val db = "migration-test.db"

    /** A three-hand Seeger night: Anna declares 24 and goes off. */
    private fun seedV1(
        variant: String,
        also: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit = {},
    ) {
        helper.createDatabase(db, 1).use { old ->
            old.execSQL(
                "INSERT INTO game_defs (id, gameKey, name, glyph, shape, direction, " +
                    "skatVariant, eliminationThreshold, defaultBuyInCents, currencySymbol, " +
                    "enabled, sortIndex) VALUES " +
                    "(1, 'skat', 'Skat', '♠', 'SINGLE_ACTOR', 'HIGHER_BETTER', '$variant', " +
                    "NULL, 1000, '€', 1, 0)"
            )
            listOf(1 to "Anna", 2 to "Ben", 3 to "Chris").forEach { (id, n) ->
                old.execSQL(
                    "INSERT INTO players (id, displayName, isRegular, retired, createdAt) " +
                        "VALUES ($id,'$n',1,0,0)"
                )
                old.execSQL(
                    "INSERT INTO session_players (sessionId, playerId, seatIndex, joinedAtRound, leftAtRound) " +
                        "VALUES (1, $id, ${id - 1}, 0, NULL)"
                )
            }
            old.execSQL("INSERT INTO sessions (id, gameDefId, startedAt, endedAt) VALUES (1, 1, 0, 10)")
            // round 0: Anna declares 24 and makes it
            old.execSQL(
                "INSERT INTO rounds (id, sessionId, roundIndex, actorPlayerId, contractValue, won, " +
                    "loserPlayerId, createdAt) VALUES (1, 1, 0, 1, 24, 1, NULL, 0)"
            )
            // round 1: Anna declares 20 and goes off
            old.execSQL(
                "INSERT INTO rounds (id, sessionId, roundIndex, actorPlayerId, contractValue, won, " +
                    "loserPlayerId, createdAt) VALUES (2, 1, 1, 1, 20, 0, NULL, 0)"
            )
            also(old)
        }
    }

    private fun scoreOf(dbase: androidx.sqlite.db.SupportSQLiteDatabase, round: Int, player: Int): Int =
        dbase.query(
            "SELECT value FROM round_entries e JOIN rounds r ON r.id = e.roundId " +
                "WHERE r.roundIndex = $round AND e.playerId = $player"
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun aSkatNightRecordedUnderTheOldSchemaKeepsItsScores() {
        seedV1("LIST")
        val migrated = helper.runMigrationsAndValidate(db, 2, true, TrackerDb.Migration1to2)

        // both rounds are still there, still attached to their session
        migrated.query("SELECT sessionId, roundIndex FROM rounds ORDER BY roundIndex").use { c ->
            assertEquals(2, c.count)
        }
        // LIST: declarer +24 then −40, opponents nil — the numbers the old
        // mode would have shown, now written down as ordinary scores
        assertEquals(24, scoreOf(migrated, 0, 1))
        assertEquals(0, scoreOf(migrated, 0, 2))
        assertEquals(-40, scoreOf(migrated, 1, 1))
        assertEquals(0, scoreOf(migrated, 1, 3))

        // and Skat is now an ordinary points game
        migrated.query("SELECT shape FROM game_defs WHERE gameKey = 'skat'").use { c ->
            c.moveToFirst()
            assertEquals("PER_PLAYER_SCORE", c.getString(0))
        }
        migrated.close()
    }

    @Test
    fun theOldVariantIsHonouredWhenConvertingNotAssumedToBeList() {
        // A table that played Seeger must not have its history silently
        // rescored as if it had played List.
        seedV1("SEEGER_FABIAN")
        val migrated = helper.runMigrationsAndValidate(db, 2, true, TrackerDb.Migration1to2)

        assertEquals(24 + 50, scoreOf(migrated, 0, 1))    // made it
        assertEquals(0, scoreOf(migrated, 0, 2))          // opponents get nothing on a win
        assertEquals(-2 * 20 - 50, scoreOf(migrated, 1, 1))
        assertEquals(40, scoreOf(migrated, 1, 2))         // ...but 40 when the declarer goes off
        assertEquals(40, scoreOf(migrated, 1, 3))
        migrated.close()
    }

    /**
     * Version 3 pins each night to the shape it was played under, because shape
     * became a setting. Nights recorded before that must be pinned to the shape
     * their game had at the time — otherwise the first person to switch Durak
     * to points reads every Durak evening they ever played as zeroes.
     */
    @Test
    fun everyExistingNightIsPinnedToTheShapeItWasPlayedUnder() {
        seedV1("LIST") { old ->
            old.execSQL(
                "INSERT INTO game_defs (id, gameKey, name, glyph, shape, direction, " +
                    "skatVariant, eliminationThreshold, defaultBuyInCents, currencySymbol, " +
                    "enabled, sortIndex) VALUES " +
                    // skatVariant was NOT NULL in v1 and every game carried one,
                    // meaningful or not — which is half of why it is gone.
                    "(2, 'durak', 'Durak', '♣', 'LOSER_ONLY', 'HIGHER_BETTER', 'LIST', " +
                    "NULL, 1000, '€', 1, 3)"
            )
            old.execSQL("INSERT INTO sessions (id, gameDefId, startedAt, endedAt) VALUES (2, 2, 0, 20)")
        }
        val migrated = helper.runMigrationsAndValidate(
            db, 3, true, TrackerDb.Migration1to2, TrackerDb.Migration2to3,
        )

        migrated.query("SELECT id, shape FROM sessions ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            // the Skat night, which version 2 turned into a points game
            assertEquals("PER_PLAYER_SCORE", c.getString(1))
            c.moveToNext()
            assertEquals("LOSER_ONLY", c.getString(1))
        }
        migrated.close()
    }
}
