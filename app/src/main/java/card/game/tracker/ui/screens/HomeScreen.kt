package card.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.data.GameDefEntity
import card.game.tracker.data.LiveSession
import card.game.tracker.domain.Direction
import card.game.tracker.domain.Scoring
import card.game.tracker.domain.Shape
import card.game.tracker.ui.DesignLab
import card.game.tracker.ui.components.DigitsText
import card.game.tracker.ui.components.GameTile
import card.game.tracker.ui.components.HeadingText
import card.game.tracker.ui.components.LiveryCard
import card.game.tracker.ui.components.MutedText
import card.game.tracker.ui.components.ScrollFade
import card.game.tracker.ui.components.TitleText
import card.game.tracker.ui.components.formatMoney
import card.game.tracker.ui.components.VSpace

@Composable
fun ColumnScope.HomeBody(
    games: List<GameDefEntity>,
    lastSession: LiveSession?,
    onPickGame: (Long) -> Unit,
) {
    // No strapline. "Tap a game to deal in" told a first-time user something
    // they worked out by looking, then went on charging a line of the grid for
    // it every night after. The tiles get the space instead.
    TitleText("Game Tracker", fontSize = 40.sp)
    VSpace(14.dp)

    // A tile is a SQUARE, always.
    //
    // It used to be whatever was left over: with eight games or fewer the grid
    // divided the sheet's height between its rows, so four games gave four tall
    // portraits and the suit floated in the middle of nothing; past eight it
    // fell back to a fixed 1:0.78, which is a letterbox. Neither is a playing
    // card, and worse, both mean the tile changes shape as you add games — so
    // the type on it changes size too, and the home screen is never twice the
    // same. A square is set by the column width alone. Add a tenth game and
    // every tile stays exactly as it was; the grid just gets longer.
    //
    // Which is why the grid scrolls rather than fits. Below a screenful it
    // centres in the space it has, so few games do not sit in a heap at the top.
    val tile = DesignLab.tiles
    val scroll = rememberScrollState()
    ScrollFade(
        canScrollBackward = { scroll.value > 0 },
        canScrollForward = { scroll.value < scroll.maxValue },
        modifier = Modifier.weight(1f),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .verticalScroll(scroll)
                    .heightIn(min = viewport),
                verticalArrangement = Arrangement.spacedBy(tile.gap, Alignment.CenterVertically),
            ) {
                games.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tile.gap),
                    ) {
                        row.forEach { g ->
                            GameTile(
                                glyph = g.glyph,
                                name = g.name,
                                onClick = { onPickGame(g.id) },
                                scale = tile,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                            )
                        }
                        // keep a lone tile the same size as a paired one
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (lastSession != null) LastSessionCard(lastSession)
            }
        }
    }
    VSpace(10.dp)
}

@Composable
private fun LastSessionCard(s: LiveSession) {
    val winners = Scoring.leaders(s.standings)
    LiveryCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            MutedText("Last session")
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                HeadingText(s.game.name, fontSize = 17.sp)
                // A ledger's totals are in cents, so they get spelled as money.
                // Printed raw, a good night at poker read "CHRIS 1032".
                val top = winners.firstOrNull()?.total
                val figure = when {
                    s.shape == Shape.LOSER_ONLY || top == null -> ""
                    s.shape == Shape.LEDGER -> "  " + formatMoney(top, s.game.currencySymbol)
                    else -> "  $top"
                }
                DigitsText(
                    text = winners.joinToString(" / ") { it.name } + figure,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
