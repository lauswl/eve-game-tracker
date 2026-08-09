package eve.game.tracker.data

import eve.game.tracker.domain.Shape
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A fully parsed and validated whole-database export, ready for one transaction. */
data class DatabaseImport(
    val games: List<GameDefEntity>,
    val players: List<PlayerEntity>,
    val sessions: List<SessionEntity>,
    val seats: List<SessionPlayerEntity>,
    val rounds: List<RoundEntity>,
    val entries: List<RoundEntryEntity>,
    val ledger: List<LedgerEntryEntity>,
)

/** Reads both the original export format and the current lossless format. */
object Importer {

    fun fromJson(json: String): DatabaseImport = try {
        parse(JSONObject(json))
    } catch (error: ImportException) {
        throw error
    } catch (error: JSONException) {
        throw ImportException("This is not a valid Eve Game Tracker export", error)
    } catch (error: IllegalArgumentException) {
        throw ImportException(error.message ?: "The export contains invalid data", error)
    }

    private fun parse(root: JSONObject): DatabaseImport {
        val format = root.requireInt("format")
        requireImport(format in 1..Exporter.FORMAT_VERSION) {
            if (format > Exporter.FORMAT_VERSION) {
                "This export was made by a newer version of Eve Game Tracker"
            } else {
                "Unsupported export format: $format"
            }
        }

        val games = root.requireArray("games").objects("games").mapIndexed { index, item ->
            val key = item.requireString("key").trim()
            val catalog = GameCatalog.all.firstOrNull { it.key == key }
            GameDefEntity(
                id = item.requirePositiveLong("id"),
                gameKey = key.also { requireImport(it.isNotEmpty()) { "A game key is blank" } },
                name = item.requireString("name").trim()
                    .also { requireImport(it.isNotEmpty()) { "A game name is blank" } },
                glyph = item.optionalString("glyph") ?: catalog?.glyph ?: "★",
                shape = item.requireEnum("shape"),
                direction = item.requireEnum("direction"),
                eliminationThreshold = item.nullableInt("eliminationThreshold"),
                defaultBuyInCents = item.requireInt("defaultBuyInCents")
                    .also { requireImport(it >= 0) { "A default buy-in is negative" } },
                currencySymbol = item.requireString("currency"),
                enabled = item.requireBoolean("enabled"),
                sortIndex = item.optionalInt("sortIndex") ?: index,
            )
        }
        requireUnique(games.map { it.id }, "game id")
        requireUnique(games.map { it.gameKey }, "game key")
        val gameIds = games.mapTo(mutableSetOf()) { it.id }

        val players = root.requireArray("players").objects("players").map { item ->
            PlayerEntity(
                id = item.requirePositiveLong("id"),
                displayName = item.requireString("name").trim()
                    .also { requireImport(it.isNotEmpty()) { "A player name is blank" } },
                isRegular = item.requireBoolean("regular"),
                retired = item.requireBoolean("retired"),
                createdAt = item.optionalLong("createdAt") ?: 0L,
            )
        }
        requireUnique(players.map { it.id }, "player id")
        val playerIds = players.mapTo(mutableSetOf()) { it.id }

        val sessions = mutableListOf<SessionEntity>()
        val seats = mutableListOf<SessionPlayerEntity>()
        val rounds = mutableListOf<RoundEntity>()
        val entries = mutableListOf<RoundEntryEntity>()
        val ledger = mutableListOf<LedgerEntryEntity>()
        var nextRoundId = 1L
        var nextLedgerId = 1L

        root.requireArray("sessions").objects("sessions").forEach { item ->
            val sessionId = item.requirePositiveLong("id")
            val gameId = item.requirePositiveLong("gameId")
            requireImport(gameId in gameIds) { "Session $sessionId refers to missing game $gameId" }
            val startedAt = item.requireLong("startedAt")
            sessions += SessionEntity(
                id = sessionId,
                gameDefId = gameId,
                startedAt = startedAt,
                endedAt = item.nullableLong("endedAt"),
                shape = item.optionalEnum<Shape>("shape"),
            )

            val sessionSeats = item.requireArray("players").objects("session players").map { seat ->
                val playerId = seat.requirePositiveLong("playerId")
                requireImport(playerId in playerIds) {
                    "Session $sessionId refers to missing player $playerId"
                }
                SessionPlayerEntity(
                    sessionId = sessionId,
                    playerId = playerId,
                    seatIndex = seat.requireInt("seat")
                        .also { requireImport(it >= 0) { "A seat index is negative" } },
                    joinedAtRound = seat.requireInt("joinedAtRound")
                        .also { requireImport(it >= 0) { "A join round is negative" } },
                    leftAtRound = seat.nullableInt("leftAtRound"),
                ).also { parsed ->
                    requireImport(parsed.leftAtRound == null || parsed.leftAtRound >= parsed.joinedAtRound) {
                        "A player leaves session $sessionId before joining"
                    }
                }
            }
            requireUnique(sessionSeats.map { it.playerId }, "player in session $sessionId")
            requireUnique(sessionSeats.map { it.seatIndex }, "seat in session $sessionId")
            seats += sessionSeats
            val seatedPlayerIds = sessionSeats.mapTo(mutableSetOf()) { it.playerId }

            val roundIndexes = mutableSetOf<Int>()
            item.requireArray("rounds").objects("rounds").forEach { round ->
                val roundIndex = round.requireInt("index")
                requireImport(roundIndex >= 0) { "A round index is negative" }
                requireImport(roundIndexes.add(roundIndex)) {
                    "Round $roundIndex appears twice in session $sessionId"
                }
                val loser = round.nullableLong("loser")
                requireImport(loser == null || loser in seatedPlayerIds) {
                    "Round $roundIndex refers to a player not seated in session $sessionId"
                }
                val roundId = nextRoundId++
                rounds += RoundEntity(
                    id = roundId,
                    sessionId = sessionId,
                    roundIndex = roundIndex,
                    loserPlayerId = loser,
                    createdAt = round.optionalLong("createdAt") ?: startedAt + roundIndex,
                )

                round.optionalObject("scores")?.let { scores ->
                    val scorePlayers = mutableSetOf<Long>()
                    scores.keys().forEach { key ->
                        val playerId = key.toLongOrNull()
                            ?: throw ImportException("A score has an invalid player id")
                        requireImport(playerId in seatedPlayerIds) {
                            "Round $roundIndex has a score for a player not seated in session $sessionId"
                        }
                        requireImport(scorePlayers.add(playerId)) {
                            "Round $roundIndex has the same player twice"
                        }
                        entries += RoundEntryEntity(roundId, playerId, scores.getInt(key))
                    }
                }
            }

            item.requireArray("ledger").objects("ledger").forEach { row ->
                val playerId = row.requirePositiveLong("playerId")
                requireImport(playerId in seatedPlayerIds) {
                    "A ledger row refers to a player not seated in session $sessionId"
                }
                ledger += LedgerEntryEntity(
                    id = nextLedgerId++,
                    sessionId = sessionId,
                    playerId = playerId,
                    kind = row.requireEnum("kind"),
                    amountCents = row.requireInt("cents")
                        .also { requireImport(it >= 0) { "A ledger amount is negative" } },
                    createdAt = row.optionalLong("createdAt") ?: startedAt + nextLedgerId,
                )
            }
        }
        requireUnique(sessions.map { it.id }, "session id")

        return DatabaseImport(games, players, sessions, seats, rounds, entries, ledger)
    }
}

class ImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

private inline fun requireImport(value: Boolean, message: () -> String) {
    if (!value) throw ImportException(message())
}

private fun <T> requireUnique(values: List<T>, label: String) {
    requireImport(values.size == values.toSet().size) { "Duplicate $label in export" }
}

private fun JSONArray.objects(label: String): List<JSONObject> = List(length()) { index ->
    optJSONObject(index) ?: throw ImportException("$label item ${index + 1} is not an object")
}

private fun JSONObject.requireArray(name: String): JSONArray =
    optJSONArray(name) ?: throw ImportException("Missing $name list")

private fun JSONObject.requireString(name: String): String =
    if (has(name) && !isNull(name)) getString(name)
    else throw ImportException("Missing $name")

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

private fun JSONObject.requireLong(name: String): Long =
    if (has(name) && !isNull(name)) getLong(name)
    else throw ImportException("Missing $name")

private fun JSONObject.requirePositiveLong(name: String): Long = requireLong(name).also {
    requireImport(it > 0) { "$name must be positive" }
}

private fun JSONObject.optionalLong(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

private fun JSONObject.nullableLong(name: String): Long? = optionalLong(name)

private fun JSONObject.requireInt(name: String): Int =
    if (has(name) && !isNull(name)) getInt(name)
    else throw ImportException("Missing $name")

private fun JSONObject.optionalInt(name: String): Int? =
    if (has(name) && !isNull(name)) getInt(name) else null

private fun JSONObject.nullableInt(name: String): Int? = optionalInt(name)

private fun JSONObject.requireBoolean(name: String): Boolean =
    if (has(name) && !isNull(name)) getBoolean(name)
    else throw ImportException("Missing $name")

private fun JSONObject.optionalObject(name: String): JSONObject? =
    if (has(name) && !isNull(name)) {
        optJSONObject(name) ?: throw ImportException("$name is not an object")
    } else null

private inline fun <reified T : Enum<T>> JSONObject.requireEnum(name: String): T {
    val raw = requireString(name)
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: throw ImportException("Unknown $name: $raw")
}

private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(name: String): T? =
    optionalString(name)?.let { raw ->
        enumValues<T>().firstOrNull { it.name == raw }
            ?: throw ImportException("Unknown $name: $raw")
    }
