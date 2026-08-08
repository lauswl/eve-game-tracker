package card.game.tracker.ui.components

import androidx.compose.ui.graphics.Color
import card.game.tracker.ui.theme.Livery

/**
 * Card suits, printed the way a deck prints them: hearts and diamonds red,
 * spades and clubs black. Nothing else.
 *
 * This is the one sanctioned exception to "no colour ever means two things".
 * A red heart is not decoration, it is what the suit *is* — a black one reads
 * as a printing error to anyone who has held a card. The ambiguity with RED =
 * being-pressed is resolved by the press itself: a pressed sticker floods its
 * whole face red and inverts every label to white, so a red glyph only ever
 * appears on a resting white face. Red field means pressed; red ink on white
 * means hearts. The two can never be on screen in the same place.
 *
 * [labelInk] is the sticker's current label colour. Suit colour is applied only
 * when that is the resting ink — pressed, ghosted and inverted stickers keep
 * their own label colour so the state always wins over the suit.
 */
fun suitInk(glyph: String, labelInk: Color): Color = when {
    labelInk != Livery.Ink -> labelInk
    glyph.startsWith("♥") || glyph.startsWith("♦") -> Livery.Red
    else -> labelInk
}

/**
 * U+FE0E, the variation selector that forces TEXT presentation.
 *
 * Without it Android renders ♥ and ♦ from the colour emoji font, which paints
 * them in *its* red on *its* rounded pillow — the wrong red, the wrong shape,
 * and immune to [suitInk]. With it the glyph comes from the text font and this
 * file decides the colour.
 */
const val TextPresentation = "︎"

/** True for the four suits, i.e. the glyphs [suitInk] has an opinion about. */
fun isSuit(glyph: String): Boolean =
    glyph.startsWith("♥") || glyph.startsWith("♦") ||
        glyph.startsWith("♠") || glyph.startsWith("♣")
