package eve.game.tracker

import eve.game.tracker.data.CatalogEntry
import eve.game.tracker.data.DefaultGames
import eve.game.tracker.data.GameCatalog
import eve.game.tracker.domain.Shape
import eve.game.tracker.ui.screens.GameSetting
import eve.game.tracker.ui.screens.settingsFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which settings each game offers.
 *
 * Worth a test rather than a look, because the failure is silent: a setting
 * that reappears on Skat is not a crash, it is a chip on a card offering to get
 * a rule wrong, and nobody notices until somebody presses it at a table.
 */
class GameSettingsTest {

    private fun game(key: String) = DefaultGames.list.first { it.gameKey == key }

    @Test
    fun `skat offers nothing, because none of its rules are opinions`() {
        assertEquals(emptyList<GameSetting>(), settingsFor(game("skat")))
    }

    /**
     * The one thing about Durak that genuinely is a choice, and the only one it
     * is offered. Counting losses and ranking the order everyone went out in are
     * both real ways to keep it, and neither is the app's to guess — but there
     * is nothing else to ask, so there is nothing else on the card.
     */
    @Test
    fun `durak is asked how it is kept, and nothing else`() {
        assertEquals(listOf(GameSetting.MODE), settingsFor(game("durak")))
    }

    @Test
    fun `uno is asked the same question, with three answers`() {
        val uno = GameCatalog.all.first { it.key == "uno" }
        assertEquals(listOf(GameSetting.MODE), settingsFor(uno.toEntity(0)))
        assertEquals(
            listOf("Winner takes a point", "Ranking", "Least cards"),
            uno.modes.map { it.label },
        )
    }

    /**
     * A choice is only a choice where two tables really disagree. Every other
     * game in the library states a fact about itself, and a chip offering to
     * disagree with a fact is only ever a way to get it wrong.
     */
    @Test
    fun `only uno and durak are asked anything about scoring`() {
        assertEquals(
            listOf("durak", "uno"),
            GameCatalog.all.filter { it.modes.isNotEmpty() }.map { it.key }.sorted(),
        )
    }

    @Test
    fun `gambio really is played low, and that is the only thing to say about it`() {
        assertEquals(listOf(GameSetting.DIRECTION), settingsFor(game("gambio")))
    }

    @Test
    fun `a ledger has one setting and it is the buy-in`() {
        assertEquals(listOf(GameSetting.BUY_IN), settingsFor(game("poker")))
    }

    @Test
    fun `no game anywhere offers a knock-out`() {
        val everything = GameCatalog.all.map { it.toEntity(0) } + DefaultGames.list
        everything.forEach { g ->
            assertEquals(
                "${g.name} still offers something it should not",
                emptyList<GameSetting>(),
                settingsFor(g).filter {
                    it !in listOf(GameSetting.DIRECTION, GameSetting.BUY_IN, GameSetting.MODE)
                },
            )
        }
    }

    @Test
    fun `the catalogue comes out alphabetical, not in the order it is written`() {
        val names = GameCatalog.search("", emptySet()).map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    /**
     * Rummikub, Schafkopf, Doppelkopf and Blackjack came out for the same reason
     * the other thirty-odd guesses did: nobody at this table plays them, and a
     * library you have to read past is not a library.
     */
    @Test
    fun `the library includes Big Two and the five original games`() {
        assertEquals(
            listOf("big_two", "durak", "gambio", "poker", "skat", "uno"),
            GameCatalog.all.map { it.key }.sorted(),
        )
    }

    @Test
    fun `a custom game keys off its name, so adding it twice is one game`() {
        val a = GameCatalog.custom("Mau-Mau", Shape.PER_PLAYER_SCORE)
        val b = GameCatalog.custom("  mau mau  ", Shape.LEDGER)
        assertEquals(a.key, b.key)
        assertEquals("custom_mau_mau", a.key)
        // and it can never collide with a catalogue key arriving later
        assertEquals(emptyList<String>(), GameCatalog.all.map { it.key }.filter { it == a.key })
    }

    @Test
    fun `a custom game with no letters in its name still gets a usable key`() {
        assertEquals("custom_game", GameCatalog.custom("!!!", Shape.LOSER_ONLY).key)
    }

    /**
     * The Add screen used to print `all.size - alreadyAdded.size`, which goes
     * negative as soon as you invent a game: its key is in the added set and was
     * never in the library. The screen announced "-1 GAMES".
     */
    @Test
    fun `what is left in the library is counted, not subtracted`() {
        val added = GameCatalog.all.map { it.key }.toSet() + "custom_kniffel"
        assertEquals(0, GameCatalog.all.count { it.key !in added })
        assertEquals(emptyList<CatalogEntry>(), GameCatalog.search("", added))
    }
}
