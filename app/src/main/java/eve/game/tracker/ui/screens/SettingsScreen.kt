package eve.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.GameCatalog
import eve.game.tracker.data.GameDefEntity
import eve.game.tracker.data.PlayerEntity
import eve.game.tracker.data.ScoringMode
import eve.game.tracker.domain.Direction
import eve.game.tracker.domain.Shape
import eve.game.tracker.ui.components.FittedHeading
import eve.game.tracker.ui.components.HSpace
import eve.game.tracker.ui.components.LiveryCard
import eve.game.tracker.ui.components.MetaText
import eve.game.tracker.ui.components.MutedText
import eve.game.tracker.ui.components.ScrollFade
import eve.game.tracker.ui.components.ScrollTail
import eve.game.tracker.ui.components.Sticker
import eve.game.tracker.ui.components.StickerLabel
import eve.game.tracker.ui.components.StickerVariant
import eve.game.tracker.ui.components.TextPresentation
import eve.game.tracker.ui.components.TitleText
import eve.game.tracker.ui.components.VSpace
import eve.game.tracker.ui.components.suitInk
import eve.game.tracker.ui.theme.Livery
import eve.game.tracker.ui.theme.LiveryType

@Composable
fun ColumnScope.SettingsBody(
    games: List<GameDefEntity>,
    players: List<PlayerEntity>,
    onRemoveGame: (GameDefEntity) -> Unit,
    onUpdateGame: (GameDefEntity) -> Unit,
    /** A change to what a round *is*. Costs the game's history — asked first. */
    onChangeScoring: (GameDefEntity) -> Unit,
    onDeleteAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAddGame: () -> Unit,
    /** Sessions on file per game, so a warning is only shown when it is true. */
    sessionCounts: Map<Long, Int> = emptyMap(),
    hasStoredData: Boolean = true,
    soundOn: Boolean = true,
    onSound: (Boolean) -> Unit = {},
) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    confirm?.let {
        ConfirmBody(it, onCancel = { confirm = null })
        return
    }

    TitleText("Setup")
    VSpace(12.dp)

    val list = rememberLazyListState()
    ScrollFade(
        canScrollBackward = { list.canScrollBackward },
        canScrollForward = { list.canScrollForward },
        modifier = Modifier.weight(1f),
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {

            items(games, key = { it.id }) { g ->
                LiveryCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The name is set as large as the row allows, which
                            // is the only way "Durak" and "Seven-Card Stud" can
                            // both be right in the same list.
                            FittedHeading(
                                text = g.name,
                                modifier = Modifier.weight(1f),
                                maxSize = 38.sp,
                                prefix = {
                                    BasicText(
                                        text = g.glyph + TextPresentation,
                                        style = LiveryType.Heading.copy(
                                            fontSize = 30.sp,
                                            color = suitInk(g.glyph, Livery.Ink),
                                        ),
                                    )
                                    HSpace(9.dp)
                                },
                            )
                            HSpace(8.dp)
                            // Not a switch. OFF left the game sitting in the
                            // list forever with no way to be rid of it, which
                            // made Setup a graveyard of games you tried once.
                            // REMOVE takes it off the home screen and out of
                            // this list, and touches no data at all: every night
                            // it was played is still on file, and adding it back
                            // from the library brings the lot with it.
                            Sticker(
                                onClick = {
                                    confirm = removeGame(g.name) { onRemoveGame(g) }
                                },
                                variant = StickerVariant.CHIP_LOUD,
                                instant = false,
                                departure = true,
                            ) { StickerLabel("Remove") }
                        }

                        settingsFor(g).forEach { setting ->
                            when (setting) {
                                // Both of these change what a recorded round
                                // means, so neither is applied on the tap that
                                // asks for it — see [Confirm].
                                GameSetting.MODE -> OptionRow(
                                    label = "Scoring",
                                    options = modesFor(g).map { m -> m.label to m },
                                    selected = currentMode(g),
                                    onPick = { mode ->
                                        if (mode != null && mode != currentMode(g)) {
                                            val next = g.copy(
                                                shape = mode.shape,
                                                direction = mode.direction,
                                            )
                                            // Nothing recorded, nothing to lose:
                                            // just change it.
                                            if ((sessionCounts[g.id] ?: 0) == 0) {
                                                onChangeScoring(next)
                                            } else {
                                                confirm = clearAndChange(g.name) {
                                                    onChangeScoring(next)
                                                }
                                            }
                                        }
                                    },
                                    stacked = true,
                                    departure = (sessionCounts[g.id] ?: 0) > 0,
                                )

                                GameSetting.DIRECTION -> OptionRow(
                                    label = "Direction",
                                    options = Direction.entries.map {
                                        (if (it == Direction.LOWER_BETTER) "Low wins" else "High wins") to it
                                    },
                                    selected = g.direction,
                                    onPick = { dir ->
                                        if (dir != g.direction) {
                                            val next = g.copy(direction = dir)
                                            if ((sessionCounts[g.id] ?: 0) == 0) {
                                                onChangeScoring(next)
                                            } else {
                                                confirm = clearAndChange(g.name) {
                                                    onChangeScoring(next)
                                                }
                                            }
                                        }
                                    },
                                    departure = (sessionCounts[g.id] ?: 0) > 0,
                                )

                                GameSetting.BUY_IN -> OptionRow(
                                    label = "Default buy-in",
                                    options = listOf(500, 1000, 2000).map {
                                        "${g.currencySymbol}${it / 100}" to it
                                    },
                                    selected = g.defaultBuyInCents,
                                    onPick = { onUpdateGame(g.copy(defaultBuyInCents = it)) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "sound") {
                LiveryCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        // The one preference in here that is not a fact about a
                        // game. A table in a quiet flat at midnight wants this
                        // off, and the phone's own volume is the wrong control
                        // for that — it is shared with everything else.
                        OptionRow(
                            label = "Sound",
                            options = listOf("On" to true, "Off" to false),
                            selected = soundOn,
                            onPick = onSound,
                        )
                    }
                }
            }

            if (players.isNotEmpty()) item(key = "players") {
                LiveryCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        MutedText("Players")
                        MetaText(
                            players.joinToString(" · ") { it.displayName },
                            Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item(key = "actions") {
                Sticker(
                    onClick = onAddGame,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    variant = StickerVariant.PRIMARY,
                    instant = false,
                    departure = true,
                ) { StickerLabel("Add a game") }

                Sticker(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    variant = StickerVariant.CHIP,
                    instant = false,
                    departure = true,
                ) { StickerLabel("Export all data") }

                Sticker(
                    onClick = { confirm = importEverything(onImport) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    variant = StickerVariant.CHIP_LOUD,
                    instant = false,
                    departure = true,
                ) { StickerLabel("Import data") }

                // Last on the screen, and loud, because it is the one control
                // here that takes something away that cannot be put back.
                Sticker(
                    onClick = {
                        // A fresh install has nothing to delete, so it is asked
                        // nothing — the button still works, it just stops
                        // pretending the press was momentous.
                        if (hasStoredData) confirm = deleteEverything { onDeleteAll() }
                        else onDeleteAll()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = StickerVariant.CHIP_LOUD,
                    instant = false,
                    departure = true,
                ) { StickerLabel("Delete all data") }
                ScrollTail()
            }
        }
    }
    VSpace(10.dp)
}

/**
 * Something held until it has been agreed to.
 *
 * Nothing is written when the chip is pressed — the chip only builds one of
 * these, and the button on the next screen is what runs it.
 *
 * Built **only when there is something to lose**. A warning about clearing a
 * game's history, shown to somebody whose history is empty, is not caution: it
 * teaches that the warnings here are theatre, and the one that matters looks
 * exactly the same as the one that did not. So Setup counts first.
 */
internal data class Confirm(
    val title: String,
    val body: String,
    val note: String,
    val confirmLabel: String,
    val apply: () -> Unit,
)

/** Changing how a game is scored, when that game has sessions on file. */
internal fun clearAndChange(game: String, apply: () -> Unit) = Confirm(
    title = "Warning",
    body = "THIS WILL CLEAR ALL EXISTING STAT DATA RELATING TO ${game.uppercase()}.",
    note = "Rounds scored the old way cannot be read the new way",
    confirmLabel = "Clear and change",
    apply = apply,
)

internal fun deleteEverything(apply: () -> Unit) = Confirm(
    title = "Warning",
    body = "THIS DELETES EVERY GAME, EVERY PLAYER AND EVERY SESSION ON FILE.",
    note = "The app starts again from nothing",
    confirmLabel = "Delete everything",
    apply = apply,
)

internal fun importEverything(apply: () -> Unit) = Confirm(
    title = "Replace data",
    body = "THIS REPLACES EVERY GAME, EVERY PLAYER AND EVERY SESSION ON FILE.",
    note = "The file is checked first — an invalid export leaves current data untouched",
    confirmLabel = "Choose import file",
    apply = apply,
)

/**
 * Taking a game off the home screen.
 *
 * Not a warning, and it does not wear the word — nothing is deleted. It is here
 * because REMOVE sits an inch from the scoring chips and undoing a mis-tap means
 * finding the game again in the library.
 */
internal fun removeGame(game: String, apply: () -> Unit) = Confirm(
    title = "Remove",
    body = "TAKE ${game.uppercase()} OFF YOUR HOME SCREEN?",
    note = "Nothing is deleted — adding it back from the library brings every " +
        "session it was played in with it",
    confirmLabel = "Remove ${game.lowercase()}",
    apply = apply,
)

/**
 * The question, printed at the size of the thing it is asking about.
 *
 * It takes the whole body rather than floating over it as a dialog: this app
 * has no dialogs, and a sheet you have to answer is the same idea without a
 * second visual language to maintain. The tab bar stays reachable above it, and
 * pressing a tab is a perfectly good way of saying no.
 */
@Composable
private fun ColumnScope.ConfirmBody(confirm: Confirm, onCancel: () -> Unit) {
    TitleText(confirm.title)
    VSpace(16.dp)
    LiveryCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            BasicText(confirm.body, style = LiveryType.Heading.copy(fontSize = 27.sp))
            MetaText(confirm.note, Modifier.padding(top = 10.dp))
        }
    }

    Spacer(Modifier.weight(1f))

    Sticker(
        onClick = { confirm.apply(); onCancel() },
        modifier = Modifier.fillMaxWidth(),
        variant = StickerVariant.PRIMARY,
        instant = false,
        departure = true,
    ) { StickerLabel(confirm.confirmLabel) }
    VSpace(10.dp)
    Sticker(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        variant = StickerVariant.NORMAL,
        instant = false,
        departure = true,
    ) { StickerLabel("Cancel") }
    VSpace(10.dp)
}

internal enum class GameSetting { MODE, DIRECTION, BUY_IN }

/** The scoring choices this game was offered when it was added, if any. */
internal fun modesFor(g: GameDefEntity): List<ScoringMode> =
    GameCatalog.all.firstOrNull { it.key == g.gameKey }?.modes.orEmpty()

/** Which of them it is being kept under now. */
internal fun currentMode(g: GameDefEntity): ScoringMode? = modesFor(g).firstOrNull {
    it.shape == g.shape &&
        (it.shape != Shape.PER_PLAYER_SCORE || it.direction == g.direction)
}

/**
 * Which settings a game actually has.
 *
 * Deliberately not derived from the shape alone. "Does low or high win" is not
 * an opinion you hold about Skat — it is a fact about Skat, and a setting for a
 * fact is only ever a way to get it wrong. Gambio really is played low, so it
 * keeps that one. Durak counts who was left holding the cards, and that is the
 * whole of it.
 *
 * **Knock-out is gone from every game.** It was a threshold you set in Setup
 * that quietly changed what the scoring screen did to you later, on games where
 * most tables do not play one at all, and the two games that do play one do not
 * agree on the number. It is out until there is a game here that actually needs
 * it. The column stays in the database, so nothing that was recorded under one
 * has been rewritten.
 *
 * There was also briefly a switch to score Durak on points. It was wrong and it
 * is gone: a Durak that keeps score does it by RANKING — first out scores more
 * than third — which is not the same thing as typing a number per hand. It put
 * a keypad in front of a game that does not want one.
 */
internal fun settingsFor(g: GameDefEntity): List<GameSetting> = when {
    // Uno and Durak, the only two with a real choice, and the one place that
    // choice can be changed after the fact — at the price of their history.
    modesFor(g).isNotEmpty() -> listOf(GameSetting.MODE)
    g.gameKey == "skat" -> emptyList()
    g.shape == Shape.LEDGER -> listOf(GameSetting.BUY_IN)
    g.shape == Shape.LOSER_ONLY -> emptyList()
    g.shape == Shape.RANKING || g.shape == Shape.WINNER_ONLY -> emptyList()
    else -> listOf(GameSetting.DIRECTION)
}

/**
 * Label plus a set of mutually exclusive chips. Every setting is this shape.
 *
 * [stacked] gives each chip a row of its own. Three chips sharing one line have
 * a third of the sheet each, and "Winner takes a point" in a third of a sheet
 * is "WINNER" — three options that all lost the word that told them apart.
 * Short answers like HIGH WINS / LOW WINS still sit side by side.
 */
@Composable
private fun <T> OptionRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onPick: (T) -> Unit,
    stacked: Boolean = false,
    departure: Boolean = false,
) {
    MutedText(label, Modifier.padding(top = 8.dp, bottom = 4.dp))
    if (stacked) {
        options.forEach { (text, value) ->
            Sticker(
                onClick = { onPick(value) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                variant = if (selected == value) StickerVariant.CHIP_ON else StickerVariant.CHIP,
                instant = false,
                departure = departure,
            ) { StickerLabel(text) }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, value) ->
                Sticker(
                    onClick = { onPick(value) },
                    modifier = Modifier.weight(1f),
                    variant = if (selected == value) StickerVariant.CHIP_ON else StickerVariant.CHIP,
                    instant = false,
                    departure = departure,
                ) { StickerLabel(text) }
            }
        }
    }
}
