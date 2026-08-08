package card.game.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Whole-database export.
 *
 * Two years of game nights living only in app-private storage is one phone
 * reset away from gone, which is the difference between a toy and something
 * worth entering data into.
 */
object Exporter {

    const val FORMAT_VERSION = 1

    fun toJson(
        games: List<GameDefEntity>,
        players: List<PlayerEntity>,
        sessions: List<SessionEntity>,
        seats: List<SessionPlayerEntity>,
        rounds: List<RoundEntity>,
        entries: List<RoundEntryEntity>,
        ledger: List<LedgerEntryEntity>,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("games", JSONArray().apply {
            games.forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("key", it.gameKey); put("name", it.name)
                    put("shape", it.shape.name); put("direction", it.direction.name)
                    put("eliminationThreshold", it.eliminationThreshold ?: JSONObject.NULL)
                    put("defaultBuyInCents", it.defaultBuyInCents)
                    put("currency", it.currencySymbol); put("enabled", it.enabled)
                })
            }
        })

        root.put("players", JSONArray().apply {
            players.forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("name", it.displayName)
                    put("regular", it.isRegular); put("retired", it.retired)
                })
            }
        })

        val seatsBySession = seats.groupBy { it.sessionId }
        val roundsBySession = rounds.groupBy { it.sessionId }
        val entriesByRound = entries.groupBy { it.roundId }
        val ledgerBySession = ledger.groupBy { it.sessionId }

        root.put("sessions", JSONArray().apply {
            sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("gameId", s.gameDefId)
                    put("startedAt", s.startedAt)
                    put("endedAt", s.endedAt ?: JSONObject.NULL)
                    put("players", JSONArray().apply {
                        seatsBySession[s.id].orEmpty().forEach { seat ->
                            put(JSONObject().apply {
                                put("playerId", seat.playerId)
                                put("seat", seat.seatIndex)
                                put("joinedAtRound", seat.joinedAtRound)
                                put("leftAtRound", seat.leftAtRound ?: JSONObject.NULL)
                            })
                        }
                    })
                    put("rounds", JSONArray().apply {
                        roundsBySession[s.id].orEmpty().sortedBy { it.roundIndex }.forEach { r ->
                            put(JSONObject().apply {
                                put("index", r.roundIndex)
                                r.loserPlayerId?.let { put("loser", it) }
                                val e = entriesByRound[r.id].orEmpty()
                                if (e.isNotEmpty()) {
                                    put("scores", JSONObject().apply {
                                        // absent player = did not play; never written as 0
                                        e.forEach { put(it.playerId.toString(), it.value) }
                                    })
                                }
                            })
                        }
                    })
                    put("ledger", JSONArray().apply {
                        ledgerBySession[s.id].orEmpty().forEach { l ->
                            put(JSONObject().apply {
                                put("playerId", l.playerId); put("kind", l.kind.name)
                                put("cents", l.amountCents)
                            })
                        }
                    })
                })
            }
        })

        return root.toString(2)
    }
}
