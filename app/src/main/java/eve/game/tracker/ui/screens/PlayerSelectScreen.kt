package eve.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.GameDefEntity
import eve.game.tracker.data.PlayerEntity
import eve.game.tracker.ui.components.BackSticker
import eve.game.tracker.ui.components.FittedHeading
import eve.game.tracker.ui.components.HSpace
import eve.game.tracker.ui.components.LiveryCard
import eve.game.tracker.ui.components.LiverySheet
import eve.game.tracker.ui.components.MetaText
import eve.game.tracker.ui.components.MutedText
import eve.game.tracker.ui.components.ScrollFade
import eve.game.tracker.ui.components.ScrollTail
import eve.game.tracker.ui.components.Sticker
import eve.game.tracker.ui.components.StickerLabel
import eve.game.tracker.ui.components.StickerVariant
import eve.game.tracker.ui.components.TextPresentation
import eve.game.tracker.ui.components.VSpace
import eve.game.tracker.ui.components.suitInk
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

@Composable
fun PlayerSelectScreen(
    game: GameDefEntity,
    players: List<PlayerEntity>,
    loadLastLineup: suspend () -> List<Long>,
    onStart: (List<Long>) -> Unit,
    onAddPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember(game.id) { mutableStateOf<List<Long>>(emptyList()) }
    var guest by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current

    // Adding a name is done; drop focus so the keyboard gets out of the way
    // instead of sitting on top of START.
    fun commitGuest() {
        if (guest.isNotBlank()) { onAddPlayer(guest.trim()); guest = "" }
        ime?.hide()
        focus.clearFocus()
    }

    // Pre-select whoever played this game last time. That is what makes the
    // common case two taps: tap the game, tap START.
    LaunchedEffect(game.id, players) {
        if (selected.isEmpty()) {
            val last = loadLastLineup().filter { id -> players.any { it.id == id } }
            if (last.isNotEmpty()) selected = last
        }
    }

    LiverySheet {
        // BACK is a peer of the title, not a chip wedged under a Primary. It
        // was the smallest target on the screen and sat where the thumb lands
        // on the way to START — the two things you least want in one control.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackSticker(onBack)
            HSpace(12.dp)
            FittedHeading(
                text = game.name,
                modifier = Modifier.weight(1f),
                maxSize = 46.sp,
                prefix = {
                    androidx.compose.foundation.text.BasicText(
                        game.glyph + TextPresentation,
                        style = LiveryType.Heading.copy(
                            fontSize = 34.sp, color = suitInk(game.glyph, Livery.Ink),
                        ),
                    )
                    HSpace(10.dp)
                },
            )
        }

        // Both halves on one left-aligned line. Right-aligned, the count ran
        // into the rake bars, and red ink on a red bar is not a legible count.
        MetaText(
            "Who is playing?" +
                if (selected.isEmpty()) "" else " · ${selected.size} at the table",
            Modifier.padding(top = 8.dp),
        )
        VSpace(12.dp)

        val scroll = rememberScrollState()
        ScrollFade(
            canScrollBackward = { scroll.value > 0 },
            canScrollForward = { scroll.value < scroll.maxValue },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                players.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { p ->
                            val on = p.id in selected
                            Sticker(
                                onClick = {
                                    ime?.hide(); focus.clearFocus()
                                    selected = if (on) selected - p.id else selected + p.id
                                },
                                modifier = Modifier.weight(1f),
                                variant = if (on) StickerVariant.CHIP_ON else StickerVariant.CHIP,
                                instant = false,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 16.dp),
                            ) {
                                // The seat number tells you the deal order and,
                                // more usefully, confirms the tap landed.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (on) {
                                        androidx.compose.foundation.text.BasicText(
                                            "${selected.indexOf(p.id) + 1}",
                                            style = LiveryType.Digits.copy(
                                                fontSize = 20.sp, color = Livery.Yellow,
                                            ),
                                        )
                                        HSpace(8.dp)
                                    }
                                    StickerLabel(p.displayName)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (players.isEmpty()) {
                    MetaText("Nobody on file yet — put a name in below")
                }
                ScrollTail()
            }
        }

        // Fixed foot, not the tail of the scroll. Four regulars left the card
        // stranded in the middle of an empty sheet, and forty would have
        // pushed it off the bottom — anchored here it is in the same place
        // either way, and always reachable without scrolling for it.
        VSpace(8.dp)
        AddPlayerCard(
            value = guest,
            onValue = { guest = it },
            onCommit = ::commitGuest,
        )

        VSpace(10.dp)
        Sticker(
            onClick = { onStart(selected) },
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.PRIMARY,
            enabled = selected.isNotEmpty(),
            departure = true,
        ) {
            StickerLabel(
                when (selected.size) {
                    0 -> "Pick who is playing"
                    1 -> "Start"
                    else -> "Start · ${selected.size}"
                },
            )
        }
    }
}

/**
 * Adding a name, given the whole width instead of a corner of a card.
 *
 * It used to be a hairline text field with a chip beside it, which at a table
 * in bad light was neither obviously a field nor obviously pressable. Now the
 * field is the size of the names it produces, it says what it wants when it is
 * empty, and ADD only lights up once there is something to add.
 */
@Composable
private fun AddPlayerCard(
    value: String,
    onValue: (String) -> Unit,
    onCommit: () -> Unit,
) {
    LiveryCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            MutedText("New player")
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        androidx.compose.foundation.text.BasicText(
                            "NAME",
                            style = LiveryType.Heading.copy(
                                fontSize = 28.sp, color = Livery.GhostText,
                            ),
                        )
                    }
                    // The only text field in the app. A keyboard here is
                    // expected; everywhere else scores go in on the keypad.
                    BasicTextField(
                        value = value,
                        onValueChange = onValue,
                        singleLine = true,
                        textStyle = LiveryType.Heading.copy(fontSize = 28.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Livery.Red),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Sticker(
                    onClick = onCommit,
                    variant = StickerVariant.CHIP_ON,
                    enabled = value.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) { StickerLabel("Add") }
            }
        }
    }
}
