package eve.game.tracker.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

/**
 * The sticker variants. Each is a column of the LIVERY state table; a screen
 * picks a variant and never invents a colour of its own.
 */
enum class StickerVariant {
    NORMAL, PRIMARY, CHIP, CHIP_ON, CHIP_LOUD, PICK, PICK_ON, TILE, KEY, TAB, TAB_ON,
}

/**
 * How long a press stays visible once it starts. Long enough to register as a
 * flash at 60 Hz (5–6 frames), short enough that a fast scorekeeper never waits
 * on it — the action has already fired by then anyway.
 */
const val MinPressMillis = 90L

/**
 * How long a sticker inside a scrollable waits before believing a touch.
 *
 * Long enough for an ancestor list to claim the gesture as a scroll or a
 * fling-catch, short enough that a deliberate press still feels instant — and
 * less than half of the 150 ms Compose's own press feedback spends waiting.
 * Nothing outside a scrollable waits at all.
 */
private const val ScrollDecisionMillis = 70L

/** Geometry and inks for one variant, in one place. */
internal data class StickerStyle(
    val padH: Dp,
    val padV: Dp,
    val slabX: Dp,
    val slabY: Dp,
    val border: Dp,
    val face: Color,
    val label: Color,
    val pressedFace: Color,
    val pressedLabel: Color,
    val textStyle: TextStyle,
)

/**
 * Padding is deliberately tight and type deliberately large: a control read
 * across a table at arm's length wants ink, not margin.
 */
internal fun styleFor(variant: StickerVariant): StickerStyle = when (variant) {
    StickerVariant.NORMAL -> StickerStyle(
        10.dp, 10.dp, 6.dp, 4.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 27.sp),
    )
    // The one loud action. It already wears the press colour at rest, so its
    // press darkens instead of reddening.
    StickerVariant.PRIMARY -> StickerStyle(
        12.dp, 10.dp, 8.dp, 5.dp, 4.dp,
        Livery.Red, Livery.Surface, Livery.RedPressed, Livery.Surface,
        LiveryType.Button.copy(fontSize = 34.sp),
    )
    StickerVariant.CHIP -> StickerStyle(
        8.dp, 9.dp, 5.dp, 4.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface,
        LiveryType.ChipLabel.copy(fontSize = 19.sp, letterSpacing = 1.2.sp),
    )
    // "This option is on." Same size as CHIP so a row never reflows when the
    // selection moves.
    StickerVariant.CHIP_ON -> StickerStyle(
        8.dp, 9.dp, 5.dp, 4.dp, 3.dp,
        Livery.Cobalt, Livery.Surface, Livery.Red, Livery.Surface,
        LiveryType.ChipLabel.copy(fontSize = 19.sp, letterSpacing = 1.2.sp),
    )
    // A chip that takes something away. Same geometry as CHIP, so a row does not
    // reflow around it, and it wears the press colour at rest like PRIMARY does
    // — so its press darkens rather than reddens.
    //
    // This is the one place red is not "being pressed". A control that removes
    // something should not look identical to one that turns a setting on, and
    // asking somebody to read the word before they know that is asking them to
    // read the word.
    StickerVariant.CHIP_LOUD -> StickerStyle(
        8.dp, 9.dp, 5.dp, 4.dp, 3.dp,
        Livery.Red, Livery.Surface, Livery.RedPressed, Livery.Surface,
        LiveryType.ChipLabel.copy(fontSize = 19.sp, letterSpacing = 1.2.sp),
    )
    // A full-width row you are choosing from a list of them. Geometry identical
    // to NORMAL, so picking one cannot make the list twitch.
    StickerVariant.PICK -> StickerStyle(
        10.dp, 10.dp, 6.dp, 4.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 27.sp),
    )
    StickerVariant.PICK_ON -> StickerStyle(
        10.dp, 10.dp, 6.dp, 4.dp, 3.dp,
        Livery.Cobalt, Livery.Surface, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 27.sp),
    )
    StickerVariant.TILE -> StickerStyle(
        4.dp, 4.dp, 6.dp, 4.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface, LiveryType.Button,
    )
    // Thumb-sized, because it is pressed forty times a night.
    StickerVariant.KEY -> StickerStyle(
        0.dp, 7.dp, 6.dp, 4.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 40.sp),
    )
    // The bottom bar is permanent furniture, so it is sized like furniture.
    StickerVariant.TAB -> StickerStyle(
        6.dp, 15.dp, 6.dp, 5.dp, 3.dp,
        Livery.Surface, Livery.Ink, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 28.sp),
    )
    StickerVariant.TAB_ON -> StickerStyle(
        6.dp, 15.dp, 6.dp, 5.dp, 3.dp,
        Livery.Cobalt, Livery.Surface, Livery.Red, Livery.Surface,
        LiveryType.Button.copy(fontSize = 28.sp),
    )
}

/**
 * A die-cut decal sitting on a solid offset ink slab.
 *
 * Pressing it makes the face TRAVEL diagonally into its own slab, which
 * collapses to 2dp behind it. Travel is derived from the slab depth, so every
 * variant presses by its own depth with no per-variant numbers, and because the
 * outer silhouette never changes size nothing in the layout moves.
 *
 * **Latency.** This deliberately does NOT use `Modifier.clickable`. Inside any
 * scrollable container Compose holds the press indication back by
 * `TapIndicationDelay` (150 ms) to see whether the gesture becomes a scroll, and
 * it only fires the click on lift. Both are wrong for a scorekeeper: the button
 * has to light up on contact and, for keys, act on contact. Here the press
 * state is set on the raw down event with no delay at all, and [instant]
 * controls whether the action fires on down (default) or on lift.
 *
 * **Minimum depress.** A fast tap can lift inside a single frame, and an
 * `instant` control that navigates away is torn out of the composition the
 * moment it fires — either way the red never gets drawn and the control feels
 * dead even though it worked. The pressed state is therefore latched for
 * [MinPressMillis] regardless of how briefly the finger was down.
 *
 * **Sound.** Presses click through the platform, so the phone's own
 * touch-sound switch governs it. That is why there is no in-app setting: an
 * app-level duplicate of a system toggle is one more thing to get out of sync.
 */
@Composable
fun Sticker(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: StickerVariant = StickerVariant.NORMAL,
    enabled: Boolean = true,
    /** Marks the entry cursor. YELLOW means "you are here" and nothing else. */
    cursor: Boolean = false,
    /**
     * Fire on touch-down. Default. Set false for stickers sitting inside a
     * scrollable surface, where a drag that starts on a control must scroll
     * rather than activate it.
     */
    instant: Boolean = true,
    /**
     * This sticker disappears when its action runs (navigation, closing a
     * sheet, and similar departures). Keep the outgoing control alive for the
     * minimum press interval so even a sub-frame tap has time to be drawn.
     * Ordinary actions and scoring keys remain genuinely instant.
     */
    departure: Boolean = false,
    contentPadding: PaddingValues? = null,
    content: @Composable () -> Unit,
) {
    val s = styleFor(variant)
    var down by remember { mutableStateOf(false) }
    // The latch keeps the red on screen long enough to be seen even when the
    // finger lifts within a frame, or the screen navigates away underneath.
    var latched by remember { mutableStateOf(false) }
    var pressSeq by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Always the latest handler, without making the handler a restart key.
    val click by rememberUpdatedState(onClick)
    var departurePending by remember { mutableStateOf(false) }

    fun activate(pressedAt: Long) {
        if (!departure) {
            click()
            return
        }
        // A double tap during the short handoff must not navigate twice.
        if (departurePending) return
        departurePending = true
        scope.launch {
            try {
                val shownFor = SystemClock.uptimeMillis() - pressedAt
                delay((MinPressMillis - shownFor).coerceAtLeast(0L))
                click()
            } finally {
                departurePending = false
            }
        }
    }

    val pressed = enabled && (down || latched)
    val faceColor = when {
        !enabled -> Livery.GhostFill
        pressed -> s.pressedFace
        cursor -> Livery.Yellow
        else -> s.face
    }
    val borderColor = if (enabled) Livery.Ink else Livery.GhostLine

    // Travel: the face slides to within 2dp of where its slab was.
    val travelX = if (pressed) (s.slabX - 2.dp).coerceAtLeast(0.dp) else 0.dp
    val travelY = if (pressed) (s.slabY - 2.dp).coerceAtLeast(0.dp) else 0.dp
    val curSlabX = if (pressed) 2.dp else s.slabX
    val curSlabY = if (pressed) 2.dp else s.slabY

    Box(
        modifier = modifier
            // Dropping `clickable` also drops its semantics, so state them
            // explicitly: screen readers and UI tests both need a button that
            // knows it is a button and knows when it is disabled.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick { onClick(); true }
            }
            // NOT keyed on onClick. A key's lambda is a fresh object on every
            // recomposition, and typing a digit recomposes — so keying on it
            // restarted this gesture detector mid-press, cancelling the wait
            // for the lift. `down` was set and never cleared, and the key
            // stayed depressed for good. `enabled` can flip mid-press too
            // (COMMIT lights up the instant a value exists), hence the finally.
            .pointerInput(enabled, instant, departure) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)

                    // Is this a press, or a finger on its way past?
                    //
                    // A list you are scrolling is a list you land on. Lighting
                    // up, clicking and making a noise because a thumb touched a
                    // game row on the way down the page is the control claiming
                    // an intent nobody had. So a sticker inside a scrollable
                    // waits to see what the gesture turns into: consumed by an
                    // ancestor (which is what catching a fling looks like) or
                    // moved past the touch slop, and it never happened.
                    //
                    // Only `instant = false` pays for this, and that flag
                    // already means "I live in a scrollable". The keypad still
                    // lights on contact with no delay, which is the whole
                    // reason this component exists.
                    var alreadyUp = false
                    if (!instant) {
                        var travel = 0f
                        val press = withTimeoutOrNull(ScrollDecisionMillis) {
                            var verdict: Boolean? = null
                            while (verdict == null) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == first.id }
                                verdict = when {
                                    c == null || c.isConsumed -> false
                                    !c.pressed -> true      // lifted, never moved
                                    else -> {
                                        travel += (c.position - c.previousPosition).getDistance()
                                        if (travel > slop) false else null
                                    }
                                }
                            }
                            verdict
                        }
                        // null is the timeout: held still and still down, which
                        // is a press somebody is making deliberately.
                        if (press == false) return@awaitEachGesture
                        alreadyUp = press == true
                    }

                    down = true
                    latched = true
                    val pressedAt = SystemClock.uptimeMillis()
                    val seq = ++pressSeq
                    scope.launch {
                        delay(MinPressMillis)
                        if (pressSeq == seq) latched = false
                    }
                    ClickSound.play()
                    if (instant) activate(pressedAt)
                    try {
                        if (alreadyUp) {
                            activate(pressedAt)
                        } else {
                            val up = waitForUpOrCancellation()
                            if (!instant && up != null) activate(pressedAt)
                        }
                    } finally {
                        down = false
                    }
                }
            }
            .drawBehind {
                val slabW = size.width - s.slabX.toPx()
                val slabH = size.height - s.slabY.toPx()
                if (slabW <= 0f || slabH <= 0f) return@drawBehind
                val tx = travelX.toPx()
                val ty = travelY.toPx()
                val faceSize = Size(slabW, slabH)

                if (enabled) {
                    drawRect(
                        color = Livery.Ink,
                        topLeft = Offset(tx + curSlabX.toPx(), ty + curSlabY.toPx()),
                        size = faceSize,
                    )
                }
                drawRect(faceColor, topLeft = Offset(tx, ty), size = faceSize)
                val bw = s.border.toPx()
                drawRect(
                    color = borderColor,
                    topLeft = Offset(tx + bw / 2f, ty + bw / 2f),
                    size = Size(faceSize.width - bw, faceSize.height - bw),
                    style = Stroke(bw),
                )
            }
            // Reserve the slab, so the content sits inside the face only.
            //
            // I tried splitting it half each side, to centre the label on the
            // whole control including its shadow. That was wrong: the white
            // face IS the button — the slab is ink printed underneath it — and
            // shifting the label off the face made every label in the app read
            // low and right. The label belongs in the middle of the face.
            .padding(end = s.slabX, bottom = s.slabY)
            .padding(contentPadding ?: PaddingValues(horizontal = s.padH, vertical = s.padV))
            // the label travels with the face
            .offsetPx(travelX, travelY),
        contentAlignment = Alignment.Center,
    ) {
        val labelColor = when {
            !enabled -> Livery.GhostText
            pressed -> s.pressedLabel
            cursor -> Livery.Ink
            else -> s.label
        }
        CompositionLocalProvider(
            LocalStickerText provides s.textStyle.copy(color = labelColor),
        ) { content() }
    }
}

/** Offset that does not disturb layout — the silhouette must stay put. */
private fun Modifier.offsetPx(x: Dp, y: Dp): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(x.roundToPx(), y.roundToPx())
    }
}

/**
 * Drops the platform's extra font padding and trims the line box to the ink.
 * Anton's line box is ~30% taller than its nominal size, which is what pushed
 * the tile subtitle off the bottom when the tile shrank.
 */
internal fun TextStyle.trimmed() = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

internal val LocalStickerText = compositionLocalOf { LiveryType.Button }

/**
 * A label that sizes itself to the sticker it is in.
 *
 * Use inside stickers whose height is imposed from outside (anything with
 * `fillMaxHeight`, i.e. every key on the keypad). A fixed font in a control
 * that shrinks just gets its ink sliced off top and bottom; this keeps the
 * glyph as large as the key can actually hold.
 */
@Composable
fun FittedStickerLabel(text: String, modifier: Modifier = Modifier, fraction: Float = 0.66f) {
    val base = LocalStickerText.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitted = with(LocalDensity.current) { (maxHeight * fraction).toSp() }
        val size = if (fitted < base.fontSize) fitted else base.fontSize
        androidx.compose.foundation.text.BasicText(
            text = text.uppercase(),
            style = base.copy(fontSize = size, lineHeight = size * 1.05f).trimmed(),
            maxLines = 1,
        )
    }
}

/**
 * The label inside a [Sticker]. Takes its style from the variant.
 *
 * Two things stop it sitting in the middle of the face, and neither is
 * obvious from the code that puts it there:
 *
 * - **Font padding.** Without [trimmed] the platform pads the line box by the
 *   font's own recommended leading, which for Anton is far bigger above than
 *   below. Centring that box put the ink low every time — most visibly on the
 *   little ON/OFF chips, where the box is barely taller than the letters.
 * - **Letter spacing.** It is added after the LAST glyph as well as between
 *   them, so the measured text is half a space wider than the ink and a
 *   centred label sits half a space to the left. It gets that half back.
 */
@Composable
fun StickerLabel(text: String, modifier: Modifier = Modifier) {
    val style = LocalStickerText.current
    val trailing = style.letterSpacing
    val nudge = with(LocalDensity.current) {
        if (trailing.isSp) (trailing.toPx() / 2f).toDp() else 0.dp
    }
    androidx.compose.foundation.text.BasicText(
        text = text.uppercase(),
        style = style.trimmed(),
        maxLines = 1,
        modifier = modifier.padding(start = nudge),
    )
}

/**
 * How much of a game tile is type, and how much is margin.
 *
 * Four sets of numbers rather than one, because this is still being chosen —
 * `home-sizes.html` draws all four at device scale and `--ei tiles 0..3` puts
 * them on the phone. [NOW] is what shipped before and is kept only as the thing
 * to compare against.
 */
enum class TileScale(
    /** Gap between tiles, both ways. */
    val gap: Dp,
    val pad: Dp,
    /** Share of the tile's height given to the suit. */
    val glyph: Float,
    /** The most of the tile's height the name may take. It may take less. */
    val nameCap: Float,
) {
    NOW(15.dp, 4.dp, 0.46f, 0.18f),
    SNUG(11.dp, 2.dp, 0.46f, 0.24f),
    TIGHT(8.dp, 0.dp, 0.42f, 0.30f),
    GRANDMA(6.dp, 0.dp, 0.38f, 0.38f),
}

/**
 * The name every tile is set to fit, whatever it actually says.
 *
 * Every name on the grid has to be the same size, and that size must not change
 * when a game is added or removed — otherwise taking Blackjack off your home
 * screen silently re-sets the type on Gambio, and the grid is never twice the
 * same. So the type is fitted to a fixed ruler rather than to the longest name
 * that happens to be on screen: the longest name in the library, which makes
 * every game in it come out identical and stay identical.
 *
 * A custom game named longer than this is the one exception — it is shrunk to
 * fit rather than printed off the edge of its tile.
 */
private val NameRuler = "DOPPELKOPF"

/**
 * A game tile: an identical white decal for every game. Identity comes from the
 * glyph and the name, never from the fill — colour already means state.
 *
 * **The name is fitted to the tile's WIDTH.** It used to be a flat share of the
 * tile's height and nothing looked at how wide the tile was, which is one rule
 * with two opposite failures. At four games the tile is 355dp tall, so the name
 * asked for 62sp, did not fit across 185dp, and was simply cut off — the phone
 * said GAMBI. At nine games the same rule asked for 24sp in a tile of the same
 * width and the name looked lost in it. Measuring the name and setting it as
 * large as the tile can actually hold fixes both at once: it can never be
 * clipped and it is never smaller than it needs to be. The height share is only
 * a ceiling now, so the suit still gets its share of a tall tile.
 *
 * There is no third line. "LOW WINS" under Gambio told you something you know if
 * you play Gambio and nothing you can act on if you don't.
 */
@Composable
fun GameTile(
    glyph: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: TileScale = TileScale.TIGHT,
) {
    Sticker(
        onClick = onClick,
        modifier = modifier,
        variant = StickerVariant.TILE,
        departure = true,
        // The grid scrolls past eight games. A tile that fires on touch-down
        // cannot be dragged past — every attempt to scroll started a game.
        instant = false,
        contentPadding = PaddingValues(scale.pad),
    ) {
        val measurer = rememberTextMeasurer()
        val label = remember(name) { name.uppercase() }
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val h = maxHeight
            val glyphSize = with(density) { (h * scale.glyph).toSp() }
            val cap = with(density) { (h * scale.nameCap).toSp() }
            // 8dp of air each side. The face already spends 3 of that on its
            // own outline, and a name set to the last available pixel reads as
            // a layout that nearly failed rather than one sized on purpose.
            val room = with(density) { (maxWidth - 16.dp).toPx().toInt() }

            fun fit(text: String, from: TextUnit): TextUnit {
                val wide = measurer.measure(
                    text = text,
                    style = LiveryType.Button.copy(fontSize = from),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
                // a glyph's advance is linear in size, so one division is the answer
                return if (wide == 0 || wide <= room) from else from * (room.toFloat() / wide)
            }

            // One size for the whole grid: what the ruler needs, or the height
            // share, whichever is smaller. It depends only on how big the tile
            // is, so it is the same on every tile and it does not move when the
            // games on the grid change.
            val shared: TextUnit = remember(room, cap) { fit(NameRuler, cap) }
            val nameSize: TextUnit = remember(label, room, shared) { fit(label, shared) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = glyph + TextPresentation,
                    style = LiveryType.Heading.copy(
                        fontSize = glyphSize, lineHeight = glyphSize,
                        // hearts and diamonds print red, as a deck does
                        color = suitInk(glyph, LocalStickerText.current.color),
                    ).trimmed(),
                    maxLines = 1,
                )
                androidx.compose.foundation.text.BasicText(
                    text = label,
                    style = LiveryType.Button.copy(
                        fontSize = nameSize, lineHeight = nameSize * 1.12f,
                        color = LocalStickerText.current.color,
                    ).trimmed(),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * A back arrow, drawn rather than typed.
 *
 * "◀" is not in Anton, so it came from whatever fallback font the phone
 * happened to have, at whatever size and with whatever side bearings that font
 * felt like — on the test device it landed in the bottom-right corner of its
 * own button. A triangle is four lines of drawing code and it is exactly where
 * it is put.
 */
@Composable
fun BackSticker(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Sticker(
        onClick = onClick,
        modifier = modifier,
        variant = StickerVariant.NORMAL,
        departure = true,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        val ink = LocalStickerText.current.color
        Canvas(Modifier.size(width = 20.dp, height = 24.dp)) {
            drawPath(
                Path().apply {
                    moveTo(0f, size.height / 2f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    close()
                },
                ink,
            )
        }
    }
}
