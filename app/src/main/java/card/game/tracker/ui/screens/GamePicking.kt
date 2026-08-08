package card.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.data.CatalogEntry
import card.game.tracker.data.GameCatalog
import card.game.tracker.data.ScoringMode
import card.game.tracker.domain.Direction
import card.game.tracker.domain.Shape
import card.game.tracker.ui.components.HeadingText
import card.game.tracker.ui.components.LiveryCard
import card.game.tracker.ui.components.LiverySheet
import card.game.tracker.ui.components.MetaText
import card.game.tracker.ui.components.MutedText
import card.game.tracker.ui.components.Sticker
import card.game.tracker.ui.components.StickerLabel
import card.game.tracker.ui.components.StickerVariant
import card.game.tracker.ui.components.TextPresentation
import card.game.tracker.ui.components.TitleText
import card.game.tracker.ui.components.VSpace
import card.game.tracker.ui.components.suitInk
import card.game.tracker.ui.theme.Livery
import card.game.tracker.ui.theme.LiveryType

/**
 * The search box, identical on both screens that pick games.
 *
 * The word SEARCH is the placeholder INSIDE the field, set in the field's own
 * type, not a caption beside it. It was a 14sp muted label sitting next to a
 * 26sp field: too small to read at arm's length, and it left the field looking
 * empty in a way that did not say what it was for. A placeholder is the right
 * size by construction — it is standing exactly where what you type will go.
 */
@Composable
fun GameSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveryCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = LiveryType.Heading.copy(fontSize = 30.sp),
                cursorBrush = SolidColor(Livery.Red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    if (query.isEmpty()) {
                        BasicText(
                            text = "SEARCH",
                            style = LiveryType.Heading.copy(
                                fontSize = 30.sp, color = Livery.GhostText,
                            ),
                        )
                    }
                    field()
                },
            )
            if (query.isNotEmpty()) {
                Sticker(
                    onClick = { onQuery("") },
                    variant = StickerVariant.CHIP,
                ) { StickerLabel("Clear") }
            }
        }
    }
}

/** One catalogue game, as a row you press. Cobalt means it is picked. */
@Composable
fun GameRow(
    entry: CatalogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    departure: Boolean = false,
) {
    Sticker(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        variant = if (picked) StickerVariant.PICK_ON else StickerVariant.PICK,
        // sits in a scrolling list, so a drag has to scroll it, not fire it
        instant = false,
        departure = departure,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val ink = if (picked) Livery.Surface else Livery.Ink
            BasicText(
                text = entry.glyph + TextPresentation,
                style = LiveryType.Heading.copy(
                    fontSize = 30.sp,
                    // a picked row is solid cobalt; a red heart on it is unreadable
                    color = if (picked) ink else suitInk(entry.glyph, ink),
                ),
            )
            // Name only. "HIGH WINS" under every second row is the same caption
            // that got cut from the game tiles, and it is no more use here — you
            // are picking a game you know by name, and everything it presets is
            // editable in Setup the moment it lands.
            //
            // Takes its colour from the sticker rather than the type role: on a
            // picked row the face is solid cobalt, and Heading's default ink on
            // that is a name you have to squint at.
            BasicText(
                text = entry.name.uppercase(),
                style = LiveryType.Heading.copy(fontSize = 24.sp, color = ink),
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

/**
 * The one question a game with a real choice gets asked, when it is added.
 *
 * Only Uno and Durak have any modes at all. Skat is high-wins and Gambio is
 * low-wins the way water is wet, and a chip offering to disagree with that is
 * only ever a way to get it wrong — so those games go straight onto the home
 * screen and this screen never appears for them.
 *
 * It is asked once and it is permanent, because it decides what a round *is*.
 * Least-losses writes a loser per round and ranking writes a points row per
 * player; there is no reading one as the other later, which is why Setup makes
 * you agree to lose the game's history before it will change this.
 */
@Composable
fun ScoringModeSheet(
    entry: CatalogEntry,
    onPick: (ScoringMode) -> Unit,
    onBack: () -> Unit,
) {
    LiverySheet {
        TitleText(entry.name, fontSize = 46.sp)
        MetaText("How do you score it?", Modifier.padding(top = 8.dp))
        VSpace(18.dp)

        // Centred, not stacked from the top. Two or three options do not fill a
        // sheet, and left at the top they read as the first three of a list
        // that got cut off — with the answer to "is that all of them?" being a
        // hand's width of blank paper below.
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            entry.modes.forEach { mode ->
                Sticker(
                    onClick = { onPick(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = StickerVariant.NORMAL,
                    instant = false,
                    departure = true,
                ) { StickerLabel(mode.label) }
                // A caption, on the one screen that has earned one: this choice
                // cannot be taken back without losing what was scored under it,
                // and "Ranking" on its own does not say what a place is worth.
                MetaText(mode.blurb, Modifier.padding(top = 6.dp, bottom = 16.dp))
            }
        }

        MetaText("Permanent — changing it later clears this game's history")
        VSpace(10.dp)
        Sticker(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.CHIP,
            instant = false,
            departure = true,
        ) { StickerLabel("Back") }
    }
}

/**
 * The way in for a game the library has never heard of.
 *
 * It appears the moment what you typed matches nothing, with the name already
 * filled in from the search box, because at that point the search box IS the
 * name field — asking you to type "Kniffel" a second time into a different
 * field would be the app pretending not to have heard you.
 *
 * The only question left is how it is scored, and there are exactly three
 * answers because there are exactly three shapes. No knock-out, no variants:
 * everything else about a game is either a fact you cannot set or a number you
 * type at the table.
 */
@Composable
fun CustomGameCard(
    name: String,
    onCreate: (CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
    departure: Boolean = false,
) {
    var shape by remember(name) { mutableStateOf(Shape.PER_PLAYER_SCORE) }
    var direction by remember(name) { mutableStateOf(Direction.HIGHER_BETTER) }
    var glyph by remember(name) { mutableStateOf("★") }

    LiveryCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            MutedText("Custom game")
            HeadingText(name, fontSize = 30.sp, modifier = Modifier.padding(top = 2.dp))

            MutedText("How it is scored", Modifier.padding(top = 12.dp, bottom = 5.dp))
            val scorings = listOf(
                Triple("High wins", Shape.PER_PLAYER_SCORE, Direction.HIGHER_BETTER),
                Triple("Low wins", Shape.PER_PLAYER_SCORE, Direction.LOWER_BETTER),
                Triple("Last loses", Shape.LOSER_ONLY, Direction.HIGHER_BETTER),
                Triple("Winner wins", Shape.WINNER_ONLY, Direction.HIGHER_BETTER),
                Triple("Ranking", Shape.RANKING, Direction.HIGHER_BETTER),
                Triple("Money", Shape.LEDGER, Direction.HIGHER_BETTER),
            )
            scorings.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pair.forEach { (label, s, d) ->
                        val on = shape == s && (s != Shape.PER_PLAYER_SCORE || direction == d)
                        Sticker(
                            onClick = { shape = s; direction = d },
                            modifier = Modifier.weight(1f),
                            variant = if (on) StickerVariant.CHIP_ON else StickerVariant.CHIP,
                            instant = false,
                        ) { StickerLabel(label) }
                    }
                }
            }

            MutedText("Suit", Modifier.padding(top = 6.dp, bottom = 5.dp))
            GameCatalog.glyphs.chunked(5).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { g ->
                        val on = g == glyph
                        Sticker(
                            onClick = { glyph = g },
                            modifier = Modifier.weight(1f),
                            variant = if (on) StickerVariant.CHIP_ON else StickerVariant.CHIP,
                            instant = false,
                        ) {
                            BasicText(
                                text = g + TextPresentation,
                                style = LiveryType.Heading.copy(
                                    fontSize = 22.sp,
                                    color = if (on) Livery.Surface else suitInk(g, Livery.Ink),
                                ),
                            )
                        }
                    }
                }
            }

            Sticker(
                onClick = { onCreate(GameCatalog.custom(name, shape, direction, glyph)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                variant = StickerVariant.PRIMARY,
                instant = false,
                departure = departure,
            ) { StickerLabel("Add ${name.trim()}") }
        }
    }
}
