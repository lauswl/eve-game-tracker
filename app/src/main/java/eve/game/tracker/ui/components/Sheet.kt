package eve.game.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.ui.DesignLab
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

private val WashPale = Color(0xFFE1E7F5)
private val WashPaler = Color(0xFFD4DEF1)

/**
 * Where content has to stop at the foot of the sheet.
 *
 * There is nothing down there any more. The car game's kerb — the red and white
 * candy stripe — was cloned, photographed on the device and cut: it was the
 * loudest piece of furniture in the app and it did no work at all. It was not
 * pressable, it did not say where you were, and it charged 34dp of every single
 * screen plus its clearance to say so. The trim keyline closes the box on its
 * own, and content clears it by the same margin it clears the top rule by.
 */
private val FootClearance = 22.dp

/** The printed sheet every screen is stuck to. */
@Composable
fun LiverySheet(
    modifier: Modifier = Modifier,
    /** Pull the saturated rake bars left when a badge caption sits top-right —
     *  pale washes may pass behind text, ink and red may not. */
    clearTopRight: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Livery.Paper)
                drawLiveryFlash(clearTopRight)
                drawTrim()
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = Livery.ScreenMargin,
                    end = Livery.ScreenMargin,
                    top = 22.dp,
                    bottom = FootClearance,
                )
                // The one screen with a text field put it at the bottom, which
                // is exactly where the soft keyboard lands: you could not see
                // the name you were typing. The sheet now stands on top of the
                // keyboard instead of under it.
                .imePadding(),
            content = content,
        )
    }
}

/**
 * The shell the three tabbed screens live in — paper, rake, trim, and the
 * bottom bar.
 *
 * The point of hoisting this above the route is that **it is not rebuilt when
 * you change tabs.** Previously every screen called `LiverySheet { … TabBar }`
 * itself, so a tab press threw away the entire composition, including the very
 * sticker that had just been pressed: the red never got a frame, and the new
 * screen's background, trim and bar were all re-laid-out and re-drawn before
 * anything appeared. Switching now swaps only [content], and the bar stays put
 * long enough to finish showing the press that caused the switch.
 */
@Composable
fun LiveryShell(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Livery.Paper)
                drawLiveryFlash(false)
                drawTrim()
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(
                        start = Livery.ScreenMargin,
                        end = Livery.ScreenMargin,
                        top = 22.dp,
                    ),
                content = content,
            )
            TabBar(
                tabs = tabs,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier
                    .padding(horizontal = Livery.ScreenMargin)
                    .padding(bottom = FootClearance, top = 12.dp),
            )
        }
    }
}

/**
 * One tabbed screen, kept alive once you have been to it.
 *
 * Switching tabs used to throw the old screen away and build the new one from
 * nothing: compose it, measure every line of type on it, record its display
 * list. Composing is the cheap part — about 12ms. Measuring thirty pieces of
 * text and recording the draw is the other 60, and it was being paid again
 * every single time you pressed a tab, for a screen that had not changed since
 * the last time you looked at it.
 *
 * So a visited tab is never taken down. It stays composed and it stays
 * measured; the only thing [active] governs is whether it gets *placed*. An
 * unplaced subtree is not drawn and cannot be touched, and coming back to it
 * costs a placement and a draw rather than a rebuild.
 *
 * A tab you have never opened is not built at all, so this costs nothing at
 * startup — you pay for Stats the first time you press Stats, once.
 */
@Composable
fun Stage(active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    // A plain holder, not snapshot state: flipping it must not schedule
    // another composition, and this composable already recomposes when
    // [active] changes, which is the only moment it can flip.
    val visited = remember { BooleanArray(1) }
    if (active) visited[0] = true
    if (!visited[0]) return

    Column(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (active) placeable.place(0, 0)
                }
            },
        content = content,
    )
}

/** Three ink bars raking off the top-right corner, leaning with the kerb. */
private fun DrawScope.drawLiveryFlash(clearTopRight: Boolean) {
    val h = size.height
    val rake = h * 0.727f          // the same 36° lean the game uses
    val w = size.width
    val shift = if (clearTopRight) -w * 0.09f else 0f

    fun bar(startFrac: Float, widthFrac: Float, color: Color) {
        val x = w * startFrac + shift
        val bw = w * widthFrac
        val p = Path().apply {
            moveTo(x, -20f)
            lineTo(x + bw, -20f)
            lineTo(x + bw + rake, h + 20f)
            lineTo(x + rake, h + 20f)
            close()
        }
        drawPath(p, color)
    }
    bar(0.44f, 0.235f, WashPale)
    bar(0.72f, 0.105f, WashPaler)
    bar(0.575f, 0.032f, Livery.Cobalt)
    bar(0.625f, 0.018f, Livery.Red)
}

/**
 * An ink keyline boxing the sheet in on all four sides.
 *
 * Inset 4dp, not 8: the keyline used to finish at 11dp and the screen margin
 * starts at 12, so a sticker's ink outline landed a single dp off the trim and
 * the two read as one smeared edge — the right-hand column of the game grid
 * looked cut off at the paper's edge. The keyline is decoration and the content
 * is not, so the keyline moved.
 */
private fun DrawScope.drawTrim() {
    val inset = 4.dp.toPx()
    val t = 3.dp.toPx()
    val bottom = size.height - inset
    drawRect(Livery.Ink, Offset(inset, inset), Size(size.width - inset * 2, t))
    drawRect(Livery.Ink, Offset(inset, inset), Size(t, bottom - inset))
    drawRect(Livery.Ink, Offset(size.width - inset - t, inset), Size(t, bottom - inset))
    drawRect(Livery.Ink, Offset(inset, bottom - t), Size(size.width - inset * 2, t))
}

/** Screen title, with the misregistered red ink pass underneath. */
@Composable
fun TitleText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 44.sp) {
    Box(modifier) {
        BasicText(
            text = text.uppercase(),
            style = LiveryType.Title.copy(color = Livery.Red, fontSize = fontSize, lineHeight = fontSize),
            modifier = Modifier.offsetDp(5.dp, 5.dp),
        )
        BasicText(
            text = text.uppercase(),
            style = LiveryType.Title.copy(fontSize = fontSize, lineHeight = fontSize),
        )
    }
}

private fun Modifier.offsetDp(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp) =
    this.then(Modifier.padding(start = x, top = y))

@Composable
fun HeadingText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 25.sp) {
    BasicText(text.uppercase(), style = LiveryType.Heading.copy(fontSize = fontSize), modifier = modifier)
}

/**
 * A heading printed as large as its box allows, stepping down only when the
 * word is genuinely too long.
 *
 * This is how you set type big without gambling. Picking a fixed size means
 * picking it for the longest name you happen to have thought of — "Durak" was
 * set at the size "Seven-Card Stud" needed and looked lost in its own card.
 * Here the size is a result, not a guess.
 *
 * It arrives in ONE pass. The first version set the type at [maxSize], let it
 * lay out, shrank it 8%, and went round again — a recomposition and a fresh
 * layout for every step, with the ink held back until it settled so the
 * step-down was never seen. On a card with four game names that is dozens of
 * text layouts before anything appears. A glyph's advance width is linear in
 * font size, so one measurement at [maxSize] and one division gives the answer
 * outright, and the type is right the first time it is drawn.
 */
@Composable
fun FittedHeading(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: androidx.compose.ui.unit.TextUnit = 40.sp,
    minSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    color: Color = Livery.Ink,
    prefix: (@Composable () -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val label = remember(text) { text.uppercase() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        prefix?.invoke()
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val room = constraints.maxWidth
            val size: TextUnit = remember(label, room) {
                val wide = measurer.measure(
                    text = label,
                    style = LiveryType.Heading.copy(fontSize = maxSize),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
                when {
                    wide == 0 || wide <= room -> maxSize
                    else -> {
                        val scaled = maxSize * (room.toFloat() / wide.toFloat())
                        if (scaled.value < minSize.value) minSize else scaled
                    }
                }
            }
            BasicText(
                text = label,
                style = LiveryType.Heading.copy(
                    fontSize = size, lineHeight = size * 1.04f, color = color,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), style = LiveryType.Meta, modifier = modifier)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), style = LiveryType.MutedLabel, modifier = modifier)
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = LiveryType.Body, modifier = modifier)
}

@Composable
fun DigitsText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Livery.Ink,
    fontSize: androidx.compose.ui.unit.TextUnit = 21.sp,
    align: TextAlign = TextAlign.End,
) {
    BasicText(
        text = text,
        style = LiveryType.Digits.copy(color = color, fontSize = fontSize, textAlign = align),
        modifier = modifier,
    )
}

/** A white panel carrying the same second ink pass as the stickers. */
@Composable
fun LiveryCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(end = 6.dp, bottom = 4.dp)
            .drawBehind {
                val sx = 6.dp.toPx()
                val sy = 4.dp.toPx()
                val bw = 3.dp.toPx()
                drawRect(Livery.Ink, Offset(sx, sy), Size(size.width, size.height))
                drawRect(Livery.Surface, Offset(0f, 0f), size)
                drawRect(
                    color = Livery.Ink,
                    topLeft = Offset(bw / 2, bw / 2),
                    size = Size(size.width - bw, size.height - bw),
                    style = Stroke(bw),
                )
            }
            .padding(3.dp),
        content = content,
    )
}

/**
 * The die-cut door badge, reused here as the round counter.
 *
 * The caption is right-aligned to the badge rather than centred: the badge sits
 * hard against the screen margin, and a centred caption runs off the sheet.
 */
@Composable
fun Roundel(number: String, caption: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Box(
            Modifier
                .size(74.dp)
                .drawBehind {
                    val r = size.minDimension / 2f
                    val bw = 4.dp.toPx()
                    // the misregistered pass is its own solid disc behind
                    drawCircle(Livery.Red, r, Offset(r + 4.dp.toPx(), r + 4.dp.toPx()))
                    drawCircle(Livery.Surface, r, Offset(r, r))
                    drawCircle(Livery.Ink, r - bw / 2, Offset(r, r), style = Stroke(bw))
                },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = number,
                style = LiveryType.Title.copy(
                    fontSize = 36.sp, lineHeight = 36.sp, fontFeatureSettings = "tnum",
                ),
            )
        }
        BasicText(
            text = caption.uppercase(),
            style = LiveryType.MutedLabel.copy(
                color = Livery.Cobalt, fontSize = 9.5.sp, textAlign = TextAlign.End,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The three-segment bottom tab bar. Cobalt marks the tab you are on.
 *
 * Chosen over the car game's full-bleed garage strip, which was cloned segment
 * for segment and compared side by side on the device. The strip is handsomer
 * as a piece of printing, but it made the three things you navigate with look
 * like structure rather than like controls, and it cost more height to do it.
 * These are buttons and they are shaped like every other button in the app.
 */
@Composable
fun TabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tabs.forEachIndexed { i, label ->
            Sticker(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                variant = if (i == selected) StickerVariant.TAB_ON else StickerVariant.TAB,
            ) { StickerLabel(label) }
        }
    }
}

/**
 * A scrolling region that never looks broken at its edges.
 *
 * Content sliced flat by an invisible viewport edge reads as a rendering fault,
 * not as "there is more" — the ink just stops mid-letter. Two things fix that
 * and neither adds a colour: the content fades into the paper over a short
 * band, and a hairline ink rule marks the edge it is passing under. Both appear
 * only on the side that actually has more content, so a list that fits shows
 * nothing at all.
 *
 * The trailing [Spacer] matters as much: without it the last row ends exactly
 * on the fold and there is no way to tell a finished list from a clipped one.
 */
@Composable
fun ScrollFade(
    /**
     * Read as lambdas, not as booleans, and deliberately.
     *
     * `canScrollBackward = scroll.value > 0` at the call site reads the scroll
     * position during COMPOSITION, so every pixel of every scroll recomposed
     * the whole screen — the entire stats page rebuilt itself sixty times a
     * second while your thumb was down. Called inside the draw lambda instead,
     * the same read only ever invalidates this one Canvas.
     */
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
    modifier: Modifier = Modifier,
    band: androidx.compose.ui.unit.Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        Canvas(Modifier.matchParentSize()) {
            val b = band.toPx()
            val hair = 2.dp.toPx()
            if (canScrollBackward()) {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Livery.Paper, Livery.Paper.copy(alpha = 0f)),
                        startY = 0f, endY = b,
                    ),
                    size = Size(size.width, b),
                )
                drawRect(Livery.Ink.copy(alpha = 0.35f), Offset(0f, 0f), Size(size.width, hair))
            }
            if (canScrollForward()) {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Livery.Paper.copy(alpha = 0f), Livery.Paper),
                        startY = size.height - b, endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - b),
                    size = Size(size.width, b),
                )
                drawRect(
                    Livery.Ink.copy(alpha = 0.35f),
                    Offset(0f, size.height - hair),
                    Size(size.width, hair),
                )
            }
        }
    }
}

/** Clearance so the last row of a scrolling list never ends on the fold. */
@Composable
fun ScrollTail() = Spacer(Modifier.height(28.dp))

@Composable
fun VSpace(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(width))
