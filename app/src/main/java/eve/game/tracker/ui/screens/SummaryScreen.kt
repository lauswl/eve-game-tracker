package eve.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.LiveSession
import eve.game.tracker.domain.Scoring
import eve.game.tracker.domain.Shape
import eve.game.tracker.domain.Standing
import eve.game.tracker.ui.components.DigitsText
import eve.game.tracker.ui.components.FittedHeading
import eve.game.tracker.ui.components.HSpace
import eve.game.tracker.ui.components.LiveryCard
import eve.game.tracker.ui.components.LiverySheet
import eve.game.tracker.ui.components.MetaText
import eve.game.tracker.ui.components.MutedText
import eve.game.tracker.ui.components.RankDisc
import eve.game.tracker.ui.components.Sticker
import eve.game.tracker.ui.components.StickerLabel
import eve.game.tracker.ui.components.StickerVariant
import eve.game.tracker.ui.components.VSpace
import eve.game.tracker.ui.components.formatMoney
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

/**
 * What happened, in the order it is asked about.
 *
 * It used to be a list: every player at the same size, in the same card, under
 * a heading that repeated the winner's name. The first question at the end of a
 * session is "who won", the second is "and who was next", and the third — asked
 * by nobody at the table, ever — is how many rounds it took. So they are set at
 * three different sizes, and the third is at the bottom.
 *
 * One way off the screen. HOME and STATS were two more ways to leave a screen
 * that already had one, and PLAY AGAIN offered to set up another session at the
 * exact moment everyone is standing up.
 */
@Composable
fun SummaryScreen(live: LiveSession, onDone: () -> Unit) {
    val standings = live.standings
    val winners = Scoring.leaders(standings)
    val rest = standings.filter { it.rank != 1 }
    val minutes = ((live.session.endedAt ?: System.currentTimeMillis()) - live.session.startedAt) / 60000

    LiverySheet {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            // ---- the answer to the only question anyone asked -------------
            LiveryCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    MutedText(if (winners.size > 1) "Tied" else "Winner")
                    VSpace(2.dp)
                    winners.forEach { w ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            // Measured, not guessed: one name gets the whole
                            // sheet at 60sp, and "Christopher" still fits on
                            // the one line it is entitled to.
                            FittedHeading(
                                text = w.name,
                                modifier = Modifier.weight(1f),
                                maxSize = if (winners.size > 1) 42.sp else 60.sp,
                            )
                            HSpace(10.dp)
                            DigitsText(
                                scoreLabel(live, w),
                                fontSize = if (winners.size > 1) 26.sp else 34.sp,
                                color = Livery.Red,
                            )
                        }
                    }
                    MetaText(live.game.name, Modifier.padding(top = 10.dp))
                }
            }

            // ---- and who was next -----------------------------------------
            if (rest.isNotEmpty()) {
                VSpace(14.dp)
                LiveryCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        MutedText("Behind")
                        VSpace(4.dp)
                        rest.forEach { s ->
                            // Second and third are read across the table; the
                            // rest are read by whoever came fourth, alone.
                            val big = s.rank <= 3
                            PlaceRow(
                                standing = s,
                                value = scoreLabel(live, s),
                                nameSize = if (big) 30.sp else 21.sp,
                                valueSize = if (big) 26.sp else 19.sp,
                                discSize = if (big) 34.dp else 24.dp,
                            )
                        }
                    }
                }
            }

            // ---- the part nobody cares about ------------------------------
            VSpace(14.dp)
            LiveryCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    MutedText("For the record", Modifier.padding(top = 6.dp))
                    if (live.shape != Shape.LEDGER) {
                        StatRow("Rounds", live.roundCount.toString())
                    }
                    StatRow(
                        "Played",
                        if (minutes < 60) "${minutes}m"
                        else "${minutes / 60}:${"%02d".format(minutes % 60)}",
                    )
                    val best = winners.firstOrNull()?.let { w ->
                        live.participants.firstOrNull { it.playerId == w.playerId }
                            ?.let { Scoring.bestRound(live.config, it, live.rounds) }
                    }
                    if (best != null) StatRow("Best round", best.toString())
                    // A player who joined late has fewer rounds than the session
                    // did; say so rather than let the average look wrong.
                    val partial = live.participants.filter { it.joinedAtRound > 0 || it.leftAtRound != null }
                    if (partial.isNotEmpty()) {
                        StatRow("Part-time", partial.joinToString(", ") { it.name })
                    }
                }
            }
        }

        VSpace(14.dp)
        Sticker(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.PRIMARY,
            departure = true,
        ) { StickerLabel("Done") }
    }
}

/** Whatever this game counts, said in that game's units. */
private fun scoreLabel(live: LiveSession, s: Standing): String = when (live.shape) {
    Shape.LEDGER -> formatMoney(s.total, live.game.currencySymbol)
    Shape.LOSER_ONLY -> "${s.total} lost"
    else -> s.total.toString()
}

@Composable
private fun PlaceRow(
    standing: Standing,
    value: String,
    nameSize: androidx.compose.ui.unit.TextUnit,
    valueSize: androidx.compose.ui.unit.TextUnit,
    discSize: androidx.compose.ui.unit.Dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankDisc(standing.rank, size = discSize)
        HSpace(10.dp)
        BasicText(
            standing.name.uppercase(),
            style = LiveryType.Heading.copy(fontSize = nameSize),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        DigitsText(value, fontSize = valueSize)
    }
}

@Composable
internal fun StatRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        MetaText(label)
        DigitsText(value, color = if (highlight) Livery.Red else Livery.Ink)
    }
}
