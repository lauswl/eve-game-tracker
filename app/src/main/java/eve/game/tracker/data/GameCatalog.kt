package eve.game.tracker.data

import eve.game.tracker.domain.Direction
import eve.game.tracker.domain.Shape

/**
 * The library of games you can add.
 *
 * Every entry is just a preset over the three shapes — no entry needs code, and
 * anything the list gets wrong for your table is editable in Setup afterwards.
 * Adding a game to this file is a one-line change; that was the whole point of
 * building shapes first.
 *
 * [keywords] carry the alternate spellings people actually search for, so
 * "cabo" finds Gambio and "doko" finds Doppelkopf.
 */
/**
 * One answer to "how is this scored?", offered when the game is added.
 *
 * Only games with a real choice carry any. Skat is high-wins and Gambio is
 * low-wins the way water is wet — offering a chip for it is only a way to get
 * it wrong. Uno and Durak are different: two tables genuinely play them two
 * ways, and neither way is the app's to guess.
 *
 * The choice is permanent, because it decides what a round *is* rather than how
 * one is displayed. Changing it later means the rounds already on file counted
 * something else, so Setup makes you agree to lose them first.
 */
data class ScoringMode(
    val label: String,
    val blurb: String,
    val shape: Shape,
    val direction: Direction = Direction.HIGHER_BETTER,
)

data class CatalogEntry(
    val key: String,
    val name: String,
    val glyph: String,
    val shape: Shape,
    val direction: Direction = Direction.HIGHER_BETTER,
    val defaultBuyInCents: Int = 1000,
    val keywords: String = "",
    /** Empty for a game whose scoring is a fact rather than a preference. */
    val modes: List<ScoringMode> = emptyList(),
) {
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return name.lowercase().contains(q) ||
            keywords.lowercase().contains(q) ||
            shapeLabel().lowercase().contains(q)
    }

    /** The same game, with the scoring you picked for it. */
    fun withMode(mode: ScoringMode): CatalogEntry =
        copy(shape = mode.shape, direction = mode.direction)

    fun shapeLabel(): String = when (shape) {
        Shape.PER_PLAYER_SCORE ->
            if (direction == Direction.LOWER_BETTER) "Low wins" else "High wins"
        Shape.LOSER_ONLY -> "Last loses"
        Shape.LEDGER -> "Ledger"
        Shape.RANKING -> "Ranking"
        Shape.WINNER_ONLY -> "Winner takes a point"
    }

    fun toEntity(sortIndex: Int) = GameDefEntity(
        gameKey = key,
        name = name,
        glyph = glyph,
        shape = shape,
        direction = direction,
        defaultBuyInCents = defaultBuyInCents,
        enabled = true,
        sortIndex = sortIndex,
    )
}

object GameCatalog {

    private fun score(
        key: String, name: String, glyph: String, low: Boolean = false,
        keywords: String = "",
    ) = CatalogEntry(
        key, name, glyph, Shape.PER_PLAYER_SCORE,
        direction = if (low) Direction.LOWER_BETTER else Direction.HIGHER_BETTER,
        keywords = keywords,
    )

    /** How Durak can be kept. Both are real; neither is guessable. */
    private val durakModes = listOf(
        ScoringMode(
            "Least losses", "One tap a round: who was left holding the cards.",
            Shape.LOSER_ONLY,
        ),
        ScoringMode(
            "Ranking", "Tap the order everyone went out in. First out scores most.",
            Shape.RANKING,
        ),
    )

    /** And Uno, which three tables play three ways. */
    private val unoModes = listOf(
        ScoringMode(
            "Winner takes a point", "One tap a round: who went out first.",
            Shape.WINNER_ONLY,
        ),
        ScoringMode(
            "Ranking", "Tap the order everyone went out in. First out scores most.",
            Shape.RANKING,
        ),
        ScoringMode(
            "Least cards", "Type what everyone was left holding. Fewest wins.",
            Shape.PER_PLAYER_SCORE, Direction.LOWER_BETTER,
        ),
    )

    /**
     * Five games, and deliberately only five: the ones actually played at this
     * table. There were forty-odd, most of them guesses — Qwirkle, Wingspan,
     * Backgammon, three separate flavours of poker. A library nobody recognises
     * is not a feature, it is a list you have to read past to find Skat, and
     * every guess in it was a preset waiting to be wrong about somebody's rules.
     * Rummikub, Schafkopf, Doppelkopf and Blackjack went the same way for the
     * same reason: nobody here plays them.
     *
     * Anything missing is one line here, or a custom game typed into the search
     * box, which is the same thing without a rebuild. The list grows from what
     * gets played.
     */
    val all: List<CatalogEntry> = listOf(
        score("skat", "Skat", "♠", keywords = "declarer german"),
        CatalogEntry("poker", "Poker", "♥", Shape.LEDGER, keywords = "cash holdem money"),
        score("gambio", "Gambio", "♦", low = true, keywords = "cabo kabo cambio"),
        CatalogEntry(
            "durak", "Durak", "♣", Shape.LOSER_ONLY,
            keywords = "fool russian", modes = durakModes,
        ),
        CatalogEntry(
            "uno", "Uno", "●", Shape.WINNER_ONLY,
            keywords = "cards", modes = unoModes,
        ),
    )

    /**
     * A game the catalogue has never heard of, built from what was typed into
     * the search box.
     *
     * The key is derived from the name so that adding "Kniffel" twice is the
     * same game twice, not two rows that both say Kniffel — [prefixed][CUSTOM]
     * so it can never collide with a catalogue key that might arrive later.
     */
    fun custom(
        name: String,
        shape: Shape,
        direction: Direction = Direction.HIGHER_BETTER,
        glyph: String = "★",
    ): CatalogEntry {
        val slug = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("").trim('_').ifEmpty { "game" }
        return CatalogEntry(
            key = CUSTOM + slug,
            name = name.trim(),
            glyph = glyph,
            shape = shape,
            direction = direction,
        )
    }

    const val CUSTOM = "custom_"

    /** The glyphs a custom game can wear. A tile is its suit and its name. */
    val glyphs = listOf("♠", "♥", "♦", "♣", "★", "●", "■", "◆", "▲", "▼")

    /**
     * Alphabetical, always. The list in [all] is grouped by shape because that
     * is how it is easiest to keep correct, but nobody browsing for a game
     * knows or cares what shape it is filed under — they know its name.
     */
    fun search(query: String, alreadyAdded: Set<String>): List<CatalogEntry> =
        all.filter { it.key !in alreadyAdded && it.matches(query) }
            .sortedBy { it.name.lowercase() }
}
