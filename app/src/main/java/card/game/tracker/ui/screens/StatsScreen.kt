package card.game.tracker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.data.GameStats
import card.game.tracker.data.PlayerRecord
import card.game.tracker.data.StatKind
import card.game.tracker.data.StatsSnapshot
import card.game.tracker.ui.components.ChartKey
import card.game.tracker.ui.components.CumulativeChart
import card.game.tracker.ui.components.DigitsText
import card.game.tracker.ui.components.FittedHeading
import card.game.tracker.ui.components.HSpace
import card.game.tracker.ui.components.LiveryCard
import card.game.tracker.ui.components.MetaText
import card.game.tracker.ui.components.MutedText
import card.game.tracker.ui.components.RankDisc
import card.game.tracker.ui.components.RateBar
import card.game.tracker.ui.components.ScrollFade
import card.game.tracker.ui.components.ScrollTail
import card.game.tracker.ui.components.TextPresentation
import card.game.tracker.ui.components.TitleText
import card.game.tracker.ui.components.VSpace
import card.game.tracker.ui.components.formatMoney
import card.game.tracker.ui.components.suitInk
import card.game.tracker.ui.theme.Livery
import card.game.tracker.ui.theme.LiveryType

/**
 * One card per game, and nothing else on the screen.
 *
 * What was here before was three layouts behind a launch flag, and every one of
 * them opened with a board adding results from different games together —
 * sessions won at Skat, at Poker and at Durak in one column, as though those
 * were the same achievement. They are not comparable, and the composite quietly
 * rewarded whoever played the game with the fewest people at the table. It is
 * gone, with the head-to-head grid and the per-player cut.
 *
 * Each card says what it counts in its own title and then answers two questions
 * in that game's own units: who is winning, and by how much. Because a game's
 * scoring is decided once when it is added, each game has exactly one honest
 * unit and the card never has to hedge — see [StatKind].
 *
 * **A bar is only ever a rate.** Points and money get a plotted line, where the
 * running total is a real quantity; everything else gets a percentage bar, which
 * is absolute and cannot flatter anyone. Nothing here draws a bar scaled to
 * whoever happens to be top, which would make a one-point lead look like a rout.
 *
 * Lazy, and it has to be: as one `verticalScroll` Column, opening the tab
 * composed and measured every game's card — every chart path, every leaderboard
 * row — before a single pixel appeared.
 */
@Composable
fun ColumnScope.StatsBody(stats: StatsSnapshot?) {
    TitleText("Stats")
    VSpace(12.dp)

    val list = rememberLazyListState()
    ScrollFade(
        canScrollBackward = { list.canScrollBackward },
        canScrollForward = { list.canScrollForward },
        modifier = Modifier.weight(1f),
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            if (stats == null || stats.games.isEmpty()) {
                item {
                    LiveryCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            MutedText("Nothing yet")
                            MetaText(
                                "Finish a session and it lands here",
                                Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            } else {
                items(stats.games, key = { it.cardKey }) { g ->
                    GameCard(g)
                    VSpace(10.dp)
                }
            }
            item { ScrollTail() }
        }
    }
    VSpace(10.dp)
}

/** Stable across recompositions, and unique when one game was played two ways. */
private val GameStats.cardKey: String get() = "$gameId·$name"

@Composable
private fun GameCard(g: GameStats) {
    LiveryCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // The title says which game; the line under it says what the number
            // in every row below actually is. A card that does not say what it
            // counts is a column of numbers you have to guess the units of.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FittedHeading(
                    text = g.name,
                    modifier = Modifier.weight(1f),
                    maxSize = 32.sp,
                    prefix = {
                        BasicText(
                            g.glyph + TextPresentation,
                            style = LiveryType.Heading.copy(
                                fontSize = 26.sp, color = suitInk(g.glyph, Livery.Ink),
                            ),
                        )
                        HSpace(8.dp)
                    },
                )
                MetaText(sessionCount(g))
            }
            MutedText(g.kind.label, Modifier.padding(top = 2.dp))

            if (g.plots && g.sessions >= 2) {
                VSpace(6.dp)
                CumulativeChart(
                    series = g.series,
                    unit = g.unit,
                    currencySymbol = g.currencySymbol,
                    lowerIsBetter = g.lowerIsBetter,
                    modifier = Modifier.fillMaxWidth(),
                )
                VSpace(6.dp)
                ChartKey(g.series)
            }

            VSpace(6.dp)
            // Ties share a disc and the next number is skipped (1, 2, 2, 4).
            // Compared on the printed number rather than the raw double: three
            // players all showing 22% but wearing 1, 2 and 3 is the card
            // claiming an order it cannot show you, and 0.2222 vs 0.2223 is not
            // an order anybody at the table would accept.
            var lastValue: String? = null
            var lastRank = 0
            g.records.forEachIndexed { i, r ->
                val value = headline(r, g)
                val rank = if (value == lastValue) lastRank else (i + 1).also { lastRank = it }
                lastValue = value
                StatRow(rank, r, g, value)
            }
        }
    }
}

/** "12 sessions", and the round count too where rounds are what is counted. */
private fun sessionCount(g: GameStats): String {
    val s = "${g.sessions} ${if (g.sessions == 1) "session" else "sessions"}"
    return if (g.plots) s else "$s · ${g.rounds} rounds"
}

@Composable
private fun StatRow(rank: Int, r: PlayerRecord, g: GameStats, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankDisc(rank, size = 30.dp)
        HSpace(9.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    r.name.uppercase(),
                    style = LiveryType.Heading.copy(fontSize = 22.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // The thing people at the table actually argue about, said in
                // words rather than left to be derived from a column.
                //
                // Only where sessions are what the card counts. A streak counts
                // sessions won, and on a card about rounds it answers a
                // question nobody asked of it — three Durak evenings where
                // everybody tied on losses printed "3 IN A ROW" against three
                // different names at once, which reads as a broken badge rather
                // than as the tie it is.
                if (r.currentStreak >= 2 && g.plots) {
                    MetaText("${r.currentStreak} in a row")
                    HSpace(8.dp)
                }
                Box(Modifier.width(86.dp), contentAlignment = Alignment.CenterEnd) {
                    DigitsText(value, fontSize = 24.sp)
                }
            }
            val rate = supportingRate(r, g)
            if (rate != null) {
                RateBar(
                    fraction = rate.toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    height = 11.dp,
                )
            }
            MetaText(supporting(r, g), Modifier.padding(top = 3.dp))
        }
    }
}

/** The one number this card is about, in that game's units. */
private fun headline(r: PlayerRecord, g: GameStats): String = when (g.kind) {
    StatKind.POINTS -> {
        val v = r.perSession
        val sign = if (v > 0 && !g.lowerIsBetter) "+" else ""
        "$sign${oneDecimal(v)}"
    }
    StatKind.MONEY -> formatMoney(r.net, g.currencySymbol)
    StatKind.LOSS_RATE -> percent(r.lossRate)
    StatKind.WIN_RATE -> percent(r.roundWinRate)
    StatKind.PLACE -> oneDecimal(r.averagePlace)
    StatKind.CARDS -> oneDecimal(r.perRound)
}

/**
 * The rate the bar draws, or null where there is no honest rate to draw.
 *
 * A ranking card bars the share of rounds won rather than the average place:
 * a place is not a fraction of anything, and scaling it to the table's best
 * player would turn a hair's-breadth lead into a full bar.
 */
private fun supportingRate(r: PlayerRecord, g: GameStats): Double? = when (g.kind) {
    StatKind.LOSS_RATE -> r.lossRate
    StatKind.WIN_RATE -> r.roundWinRate
    StatKind.PLACE -> r.roundWinRate
    StatKind.POINTS, StatKind.MONEY, StatKind.CARDS -> null
}

/** The working under the headline: what the number was made of. */
private fun supporting(r: PlayerRecord, g: GameStats): String = when (g.kind) {
    StatKind.POINTS -> "${r.wins}/${r.sessions} sessions won · ${signed(r.net)} total"
    StatKind.MONEY ->
        "${r.wins}/${r.sessions} sessions won · " +
            "${formatMoney((r.perSession).toInt(), g.currencySymbol)} a session"
    StatKind.LOSS_RATE -> "${r.roundsLost} of ${r.rounds} rounds"
    StatKind.WIN_RATE -> "${r.roundsWon} of ${r.rounds} rounds"
    StatKind.PLACE -> "won ${percent(r.roundWinRate)} of ${r.rounds} rounds"
    StatKind.CARDS -> "${r.net} over ${r.rounds} rounds"
}

private fun percent(v: Double): String = "${Math.round(v * 100)}%"

private fun oneDecimal(v: Double): String {
    val rounded = Math.round(v * 10)
    return "${rounded / 10}.${kotlin.math.abs(rounded % 10)}"
}

private fun signed(v: Int): String = if (v > 0) "+$v" else v.toString()
