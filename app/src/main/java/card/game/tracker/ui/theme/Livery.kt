package card.game.tracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.R

/**
 * LIVERY — cloned from the Streetbound car game's `ui_theme.gd`.
 *
 * Motorsport decal poster: white paper, saturated flat inks, screen-printed
 * type. Every control is a die-cut sticker — hard edges, no corner radius, thick
 * ink outline, solid offset ink slab underneath.
 *
 * Colour is a language, and no colour ever means two things:
 *
 *   WHITE   pressable, nothing special about it
 *   COBALT  structure / navigation, and "this option is on"
 *   RED     being pressed right now — and the one loud action at rest
 *   YELLOW  you are here (the entry cursor)
 *   GHOST   unavailable
 *
 * The direct consequence for this app: game tiles are NOT colour-coded. Making
 * Skat green would give colour a second meaning and the whole system stops
 * reading. Identity comes from the glyph and the name instead.
 */
object Livery {
    val Paper = Color(0xFFF3F5F9)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14161C)
    val Red = Color(0xFFF0201C)
    val RedPressed = Color(0xFFAD1714)   // Red darkened 28%, as the game does
    val Cobalt = Color(0xFF1434C8)
    val CobaltLight = Color(0xFF2646D6)
    val Yellow = Color(0xFFFFCD00)
    val BodyInk = Color(0xFF2B303C)
    val Muted = Color(0xFF7A8598)
    val GhostLine = Color(0xFFB1BBCB)
    val GhostText = Color(0xFF7F8B9E)
    val GhostFill = Color(0xFFE7ECF3)
    val Wash = Color(0xFFE4E9F6)

    /** The only fixed inset in the system. */
    val ScreenMargin = 12.dp
}

val Anton = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)
val BarlowBody = FontFamily(Font(R.font.barlow_semicondensed_medium, FontWeight.Medium))

/**
 * The type roles. A screen picks a role; it never invents a size or a colour.
 */
object LiveryType {
    /** Screen titles. The misregistered red ink pass is drawn by `TitleText`. */
    val Title = TextStyle(
        fontFamily = Anton, fontSize = 56.sp, lineHeight = 56.sp,
        letterSpacing = 0.7.sp, color = Livery.Ink,
    )

    /** Game and screen names. */
    val Heading = TextStyle(
        fontFamily = Anton, fontSize = 34.sp, lineHeight = 36.sp,
        letterSpacing = 0.3.sp, color = Livery.Ink,
    )

    /** Pit-board spec data: red ink, wide tracking, always upper case. */
    val Meta = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 19.sp, letterSpacing = 1.6.sp,
        color = Livery.Red,
    )

    /** The small print at the foot of the poster. */
    val MutedLabel = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp,
        color = Livery.Muted,
    )

    val Body = TextStyle(
        fontFamily = BarlowBody, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 25.sp, color = Livery.BodyInk,
    )

    /**
     * Anything that counts.
     *
     * Barlow Condensed honours `tnum`, so every figure is the same width and a
     * score column does not shuffle sideways as it fills. This is what
     * "monospace font" in the original notes was actually asking for — a real
     * monospace face would fight Anton everywhere else.
     */
    val Digits = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontSize = 27.sp, lineHeight = 29.sp, letterSpacing = 0.2.sp,
        color = Livery.Ink, fontFeatureSettings = "tnum",
        textAlign = TextAlign.End,
    )

    /** Sticker labels. */
    val Button = TextStyle(
        fontFamily = Anton, fontSize = 24.sp, lineHeight = 26.sp,
        letterSpacing = 0.9.sp,
    )

    val ChipLabel = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 19.sp, letterSpacing = 1.4.sp,
    )
}

