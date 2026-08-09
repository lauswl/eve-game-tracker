package eve.game.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.PlayerSeries
import eve.game.tracker.data.StatUnit
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

/**
 * Charts in a system where colour is already a language.
 *
 * A normal chart library hands every series its own hue, which here would make
 * a line for "Ben" indistinguishable from a control that is being pressed. So
 * these draw the way a printed chart drew before colour presses were cheap:
 * one ink, and series told apart by **pen** — weight and dash — with the name
 * set at the end of its own line. It costs nothing in legibility up to about
 * six players, which is more than fit round a table anyway.
 */
/** Width of the scale column to the left of every plot. */
private val Gutter = 42.dp

private data class Pen(val width: Dp, val dash: FloatArray?)

private val Pens = listOf(
    Pen(4.5.dp, null),                              // leader: solid and heavy
    Pen(3.5.dp, floatArrayOf(16f, 9f)),
    Pen(3.5.dp, floatArrayOf(3f, 8f)),
    Pen(3.dp, floatArrayOf(20f, 7f, 3f, 7f)),
    Pen(2.5.dp, floatArrayOf(9f, 6f)),
    Pen(2.dp, null),
)

private fun DrawScope.strokeFor(i: Int): Stroke {
    val pen = Pens[i % Pens.size]
    return Stroke(
        width = pen.width.toPx(),
        cap = StrokeCap.Butt,
        pathEffect = pen.dash?.let { PathEffect.dashPathEffect(it.map { v -> v.dp.toPx() / 2f }.toFloatArray()) },
    )
}

/** The key: the pen sample, then the name. Same order as the plot. */
@Composable
fun ChartKey(series: List<PlayerSeries>, modifier: Modifier = Modifier) {
    Column(modifier) {
        series.forEachIndexed { i, s ->
            Row(
                Modifier.padding(top = if (i == 0) 0.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.width(34.dp).height(10.dp)) {
                    drawLine(
                        color = Livery.Ink,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = strokeFor(i).width,
                        pathEffect = strokeFor(i).pathEffect,
                    )
                }
                HSpace(8.dp)
                BasicText(
                    s.name.uppercase(),
                    style = LiveryType.MutedLabel.copy(fontSize = 15.sp, color = Livery.Ink),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Every player's running total, session by session.
 *
 * The zero line is drawn in cobalt because it is structure, not data — it is
 * the same rule that makes table headers cobalt. Above it you are up on the
 * night, below it you are down, and for a game where low wins the plot is
 * flipped so up still means winning.
 */
@Composable
fun CumulativeChart(
    series: List<PlayerSeries>,
    unit: StatUnit,
    currencySymbol: String,
    lowerIsBetter: Boolean,
    modifier: Modifier = Modifier,
) {
    val points = series.filter { it.cumulative.isNotEmpty() }
    if (points.isEmpty()) return
    val n = points.maxOf { it.cumulative.size }
    val flip = if (lowerIsBetter) -1 else 1
    val values = points.flatMap { it.cumulative }.map { it * flip }
    val lo = minOf(values.min(), 0)
    val hi = maxOf(values.max(), 0)
    val span = (hi - lo).coerceAtLeast(1)

    Box(modifier) {
        // The scale, so the shape of the plot can be read as a size and not
        // just a direction. It gets its own gutter rather than being set into
        // the plot: laid over the top-left corner it landed underneath the
        // leader's line, which is exactly where a leader's line goes.
        Column(Modifier.width(Gutter).height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                formatStat(hi * flip, unit, currencySymbol, !lowerIsBetter),
                style = LiveryType.MutedLabel.copy(fontSize = 12.sp),
                maxLines = 1,
            )
            BasicText(
                formatStat(lo * flip, unit, currencySymbol, !lowerIsBetter),
                style = LiveryType.MutedLabel.copy(fontSize = 12.sp),
                maxLines = 1,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val padL = Gutter.toPx()
            val padR = 10.dp.toPx()
            // Breathing room top and bottom. Without it the lowest line lands
            // exactly on the edge of the canvas and its end tick is sliced in
            // half, which reads as a clipping bug rather than as a low score.
            val padV = 8.dp.toPx()
            val w = size.width - padR - padL
            val h = size.height - padV * 2
            fun x(i: Int) = padL + if (n <= 1) w else w * i / (n - 1f)
            fun y(v: Int) = padV + h - (v * flip - lo).toFloat() / span * h

            // Zero, if it is inside the plot at all. There is deliberately no
            // rule along the floor of the plot: a heavy line at the bottom of
            // the box is not zero, it is the edge of the paper, and drawn in
            // ink it reads as one more player having a very bad season.
            if (lo < 0 && hi > 0) {
                drawLine(
                    color = Livery.Cobalt,
                    start = Offset(padL, y(0)),
                    end = Offset(size.width, y(0)),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }

            points.forEachIndexed { idx, s ->
                val path = androidx.compose.ui.graphics.Path()
                s.cumulative.forEachIndexed { i, v ->
                    if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                }
                drawPath(path, Livery.Ink, style = strokeFor(idx))
                // a solid tick at the head of the line, so the current
                // standing is readable without following the whole trace
                val last = s.cumulative.lastIndex
                drawRect(
                    Livery.Ink,
                    Offset(x(last) - 1.dp.toPx(), y(s.cumulative[last]) - 4.dp.toPx()),
                    Size(8.dp.toPx(), 8.dp.toPx()),
                )
            }
        }
    }
}

/**
 * One player's whole history in the width of a table cell.
 *
 * The reason this exists next to the number: a total of +40 reads the same
 * whether it was won steadily or won once in 2023 and bled since.
 */
@Composable
fun Sparkline(
    values: List<Int>,
    modifier: Modifier = Modifier,
    lowerIsBetter: Boolean = false,
) {
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val flip = if (lowerIsBetter) -1 else 1
    val v = values.map { it * flip }
    val lo = minOf(v.min(), 0)
    val hi = maxOf(v.max(), 0)
    val span = (hi - lo).coerceAtLeast(1)
    Canvas(modifier) {
        fun x(i: Int) = size.width * i / (v.lastIndex.toFloat())
        fun y(a: Int) = size.height - (a - lo).toFloat() / span * size.height
        if (lo < 0 && hi > 0) {
            drawLine(
                Livery.GhostLine,
                Offset(0f, y(0)), Offset(size.width, y(0)),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        val path = androidx.compose.ui.graphics.Path()
        v.forEachIndexed { i, a -> if (i == 0) path.moveTo(x(i), y(a)) else path.lineTo(x(i), y(a)) }
        drawPath(path, Livery.Ink, style = Stroke(2.5.dp.toPx()))
        drawRect(
            Livery.Ink,
            Offset(size.width - 5.dp.toPx(), y(v.last()) - 2.5.dp.toPx()),
            Size(5.dp.toPx(), 5.dp.toPx()),
        )
    }
}

/**
 * A win rate as a filled bar. Cobalt fill, because the bar is a measure of the
 * board and not a thing you can press.
 */
@Composable
fun RateBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    fill: Color = Livery.Cobalt,
) {
    Box(
        modifier
            .height(height)
            .drawBehind {
                val bw = 2.5.dp.toPx()
                drawRect(Livery.Surface)
                drawRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                drawRect(
                    Livery.Ink,
                    Offset(bw / 2, bw / 2),
                    Size(size.width - bw, size.height - bw),
                    style = Stroke(bw),
                )
            },
    )
}

/** A small die-cut disc carrying a rank. The badge, shrunk to list size. */
@Composable
fun RankDisc(rank: Int, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension / 2f
                val bw = 3.dp.toPx()
                // only the leader gets the misregistered second pass
                if (rank == 1) drawCircle(Livery.Red, r, Offset(r + 3.dp.toPx(), r + 3.dp.toPx()))
                drawCircle(Livery.Surface, r, Offset(r, r))
                drawCircle(Livery.Ink, r - bw / 2, Offset(r, r), style = Stroke(bw))
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = rank.toString(),
            style = LiveryType.Title.copy(
                fontSize = size.value.times(0.52f).sp,
                lineHeight = size.value.times(0.52f).sp,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/**
 * Formats a number in whatever the game actually counts.
 *
 * [signed] is false for a game where low wins: those points are penalties, and
 * "+30" for the best night of Gambio anyone ever had reads as a gain.
 */
fun formatStat(
    value: Int,
    unit: StatUnit,
    currencySymbol: String,
    signed: Boolean = true,
): String = when (unit) {
    StatUnit.MONEY -> {
        val sign = if (value < 0) "−" else if (value > 0) "+" else ""
        val abs = kotlin.math.abs(value)
        "$sign$currencySymbol${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
    StatUnit.LOSSES -> value.toString()
    StatUnit.POINTS -> if (signed && value > 0) "+$value" else value.toString()
}

/** A label and a number, set as a pair. The unit of the summary strip. */
@Composable
fun StatFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueSize: androidx.compose.ui.unit.TextUnit = 30.sp,
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        BasicText(
            text = value,
            style = LiveryType.Digits.copy(
                fontSize = valueSize,
                lineHeight = valueSize,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            ),
            maxLines = 1,
        )
        BasicText(
            text = label.uppercase(),
            style = LiveryType.MutedLabel.copy(fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

/** The summary strip: three or four figures across, evenly spread. */
@Composable
fun FigureStrip(figures: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        figures.forEach { (value, label) -> StatFigure(value, label) }
    }
}
