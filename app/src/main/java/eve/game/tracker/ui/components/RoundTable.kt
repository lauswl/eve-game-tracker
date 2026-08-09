package eve.game.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.LiveSession
import eve.game.tracker.domain.Scoring
import eve.game.tracker.domain.Shape
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

/**
 * The score table: one column per player, one row per round, running totals
 * pinned at the foot.
 *
 * A player who joined mid-session has no entry for the earlier rounds, and
 * those cells read "–". They are emphatically not zeros — a zero is a score
 * somebody actually got.
 */
@Composable
fun RoundTable(
    live: LiveSession,
    modifier: Modifier = Modifier,
    /** The draft row being typed, keyed by player. */
    draft: Map<Long, String> = emptyMap(),
    cursorPlayerId: Long? = null,
    onCellTap: ((roundIndex: Int, playerId: Long) -> Unit)? = null,
) {
    val participants = live.participants
    val standings = live.standings
    val leaderIds = remember(standings) { Scoring.leaders(standings).map { it.playerId }.toSet() }
    val totals = remember(standings) { standings.associate { it.playerId to it.total } }
    val scroll = rememberScrollState()
    val wide = participants.size > 4

    val rowScroll = rememberScrollState()
    // keep the newest round in view as the table fills
    LaunchedEffect(live.rounds.size) { rowScroll.animateScrollTo(rowScroll.maxValue) }

    LiveryCard(modifier) {
        Column(Modifier.then(if (wide) Modifier.horizontalScroll(scroll) else Modifier)) {
            // Wider columns, because the digits in them are wider now. Five
            // players and up scroll sideways rather than squeezing.
            val colWidth = if (wide) Modifier.width(88.dp) else Modifier.weight(1f)

            // header — a printed cobalt strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Livery.Cobalt),
            ) {
                Cell("#", Modifier.width(38.dp), color = Livery.Surface, header = true, align = TextAlign.Start)
                participants.forEach { p ->
                    Cell(p.name, colWidth, color = Livery.Surface, header = true)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Livery.Ink),
            )

            // Only the rounds scroll. The header and the running totals are
            // pinned — the totals are the thing you look at, so they must never
            // be the thing that scrolls away.
            //
            // fill = true, so the rounds take every pixel the card has and the
            // totals sit on its bottom edge. With fill = false the block was
            // only as tall as the rounds in it, and a young session left a band
            // of blank card under the totals with the ink rule floating across
            // the middle of it.
            Column(Modifier.weight(1f).verticalScroll(rowScroll)) {
            live.rounds.forEach { round ->
                Row(Modifier.fillMaxWidth()) {
                    Cell(
                        "%02d".format(round.index + 1), Modifier.width(38.dp),
                        color = Livery.Muted, small = true, align = TextAlign.Start,
                    )
                    participants.forEach { p ->
                        val v = Scoring.cellValue(live.config, round, p)
                        val isLoser = live.shape == Shape.LOSER_ONLY && round.loserId == p.playerId
                        Cell(
                            text = cellText(live, v, isLoser),
                            // Durak's whole record is one mark per round, so
                            // the mark is the data and gets the size the
                            // totals get, not the size a digit gets.
                            big = live.shape == Shape.LOSER_ONLY && isLoser,
                            color = if (isLoser) Livery.Red else Livery.Ink,
                            modifier = colWidth.then(
                                if (onCellTap != null) {
                                    Modifier.clickable { onCellTap(round.index, p.playerId) }
                                } else Modifier
                            ),
                        )
                    }
                }
                HairRule()
            }

            // the round being typed right now
            if (draft.isNotEmpty() || cursorPlayerId != null) {
                Row(Modifier.fillMaxWidth()) {
                    Cell(
                        "%02d".format(live.rounds.size + 1), Modifier.width(38.dp),
                        color = Livery.Red, small = true, align = TextAlign.Start,
                    )
                    participants.forEach { p ->
                        val isCursor = p.playerId == cursorPlayerId
                        val txt = draft[p.playerId].orEmpty()
                        Cell(
                            text = if (isCursor) "${txt}_" else txt.ifEmpty { "–" },
                            modifier = colWidth,
                            color = if (isCursor) Livery.Ink else Livery.Red,
                            background = if (isCursor) Livery.Yellow else null,
                        )
                    }
                }
                HairRule()
            }
            }

            // totals
            Box(Modifier.fillMaxWidth().height(3.dp).background(Livery.Ink))
            Row(Modifier.fillMaxWidth()) {
                Cell("Σ", Modifier.width(38.dp), align = TextAlign.Start)
                participants.forEach { p ->
                    Cell(
                        text = totalText(live, totals[p.playerId] ?: 0),
                        modifier = colWidth,
                        color = if (p.playerId in leaderIds) Livery.Red else Livery.Ink,
                        big = true,
                    )
                }
            }
        }
    }
}

/**
 * "×", not "✗". The ballot X is not in Barlow Condensed, so it arrived from
 * whatever symbol font the phone had — thin, slanted, and visibly a different
 * typeface from every number beside it. The multiplication sign is in the face
 * itself and comes out as heavy as the digits do. Same mark, one code point
 * along, no fallback.
 */
private fun cellText(live: LiveSession, value: Int?, isLoser: Boolean): String = when {
    value == null -> "–"
    live.shape == Shape.LOSER_ONLY -> if (isLoser) "×" else "·"
    else -> value.toString()
}

private fun totalText(live: LiveSession, total: Int): String =
    if (live.shape == Shape.LEDGER) formatMoney(total, live.game.currencySymbol)
    else total.toString()

fun formatMoney(cents: Int, symbol: String): String {
    val sign = if (cents < 0) "−" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$symbol${abs / 100}." + "%02d".format(abs % 100)
}

@Composable
private fun HairRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Livery.Wash))
}

@Composable
private fun Cell(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = Livery.Ink,
    header: Boolean = false,
    small: Boolean = false,
    big: Boolean = false,
    align: TextAlign = TextAlign.End,
    background: androidx.compose.ui.graphics.Color? = null,
) {
    Box(
        modifier
            .then(if (background != null) Modifier.background(background) else Modifier)
            .padding(horizontal = 5.dp, vertical = 6.dp),
        contentAlignment = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        // A score table read across a table at arm's length, not a spreadsheet.
        // These were 11/12/17/19sp — small enough that the number you had just
        // typed was the least legible thing on a screen otherwise made of
        // 40sp keys. Barlow Condensed is narrow, so the extra size costs far
        // less width than it looks like it should.
        val style = when {
            header -> LiveryType.MutedLabel.copy(
                color = color, fontSize = 18.sp, textAlign = align,
            )
            small -> LiveryType.Digits.copy(color = color, fontSize = 16.sp, textAlign = align)
            big -> LiveryType.Digits.copy(color = color, fontSize = 30.sp, textAlign = align)
            else -> LiveryType.Digits.copy(color = color, fontSize = 24.sp, textAlign = align)
        }
        BasicText(if (header) text.uppercase() else text, style = style, maxLines = 1)
    }
}
