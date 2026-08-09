package eve.game.tracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The in-app numeric keypad.
 *
 * The app never opens the system IME. A soft keyboard would cost a mode switch
 * per entry, reflow the table every time it animates in, and give us a keyboard
 * transition to make glitch-free for no benefit. There is no keyboard to
 * animate if there is no keyboard.
 */
@Composable
fun Keypad(
    onDigit: (Int) -> Unit,
    /** Toggles the minus. There is no plus: a score is positive until you say so. */
    onNegate: () -> Unit,
    onBackspace: () -> Unit,
    onCommit: () -> Unit,
    commitLabel: String,
    commitEnabled: Boolean,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = true,
    /** What that key says. A ledger has no minus, so it borrows the key. */
    negateLabel: String = "-",
    /** A second outcome sharing the commit row, e.g. Skat's LOST next to WON. */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
) {
    val gap = 7.dp
    // Every row is weighted, so the pad fills exactly the space the screen can
    // spare and the table above it is never sliced. Keys get as large as they
    // can be rather than as large as their text happens to make them.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        listOf(listOf(7, 8, 9), listOf(4, 5, 6), listOf(1, 2, 3)).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { d ->
                    Sticker(
                        onClick = { onDigit(d) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        variant = StickerVariant.KEY,
                    ) { FittedStickerLabel(d.toString()) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Sticker(
                onClick = onNegate,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                variant = StickerVariant.KEY,
                enabled = allowNegative,
                // Just the minus. A plus key had nothing to do — a number is
                // positive already, so it was a key whose only job was to undo
                // the key beside it. Pressing this twice still does that.
                //
                // "±" and "⌫" both fall back to a system font: thin, boxy, and
                // nothing like Anton's heavy ink next to them. Set in the
                // app's own face instead.
            ) { FittedStickerLabel(negateLabel) }
            Sticker(
                onClick = { onDigit(0) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                variant = StickerVariant.KEY,
            ) { FittedStickerLabel("0") }
            Sticker(
                onClick = onBackspace,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                variant = StickerVariant.KEY,
            ) { FittedStickerLabel("Del") }
        }
        // The one loud action on this screen: the verb you press forty times a
        // night. Ending a session is an ordinary white chip, not this.
        //
        // The same height as a key, not 1.15 of one. It was the tallest thing on
        // the screen for no reason but emphasis, and it is already the only red
        // control on it — the colour does that job without spending the rows of
        // the table above it.
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (secondaryLabel != null && onSecondary != null) {
                Sticker(
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    variant = StickerVariant.NORMAL,
                    enabled = secondaryEnabled,
                ) { FittedStickerLabel(secondaryLabel, fraction = 0.5f) }
            }
            Sticker(
                onClick = onCommit,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                variant = StickerVariant.PRIMARY,
                enabled = commitEnabled,
            ) { FittedStickerLabel(commitLabel, fraction = 0.5f) }
        }
    }
}
