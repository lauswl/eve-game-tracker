package card.game.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.data.LiveSession
import card.game.tracker.domain.LedgerKind
import card.game.tracker.domain.Participant
import card.game.tracker.domain.Scoring
import card.game.tracker.domain.Shape
import card.game.tracker.ui.components.DigitsText
import card.game.tracker.ui.components.FittedStickerLabel
import card.game.tracker.ui.components.HeadingText
import card.game.tracker.ui.components.Keypad
import card.game.tracker.ui.components.LiveryCard
import card.game.tracker.ui.components.LiverySheet
import card.game.tracker.ui.components.MetaText
import card.game.tracker.ui.components.MutedText
import card.game.tracker.ui.components.RoundTable
import card.game.tracker.ui.components.Sticker
import card.game.tracker.ui.components.StickerLabel
import card.game.tracker.ui.components.StickerVariant
import card.game.tracker.ui.components.VSpace
import card.game.tracker.ui.components.formatMoney
import card.game.tracker.ui.theme.Livery

/**
 * The scoring screen: four genuinely different layouts on one skeleton —
 * header, scrolling history, pinned totals, entry dock.
 */
@Composable
fun ScoringScreen(
    live: LiveSession,
    onRecordScores: (Map<Long, Int>) -> Unit,
    onRecordLoser: (Long?) -> Unit,
    onLedger: (Long, LedgerKind, Int) -> Unit,
    onUndo: () -> Unit,
    onJoin: (Long) -> Unit,
    onLeave: (Long) -> Unit,
    onEnd: () -> Unit,
) {
    val active = live.participants.filter { it.leftAtRound == null }

    // The round being typed lives in the table, not in a card of its own — one
    // table, one idiom, and it leaves the table room to actually be a table.
    var draft by remember(live.session.id) { mutableStateOf(mapOf<Long, String>()) }
    var cursor by remember(live.session.id) { mutableIntStateOf(0) }
    val idx = cursor.coerceIn(0, (active.size - 1).coerceAtLeast(0))
    val typing = active.getOrNull(idx)

    // NO HEADER. This is the one screen where vertical space is the scarce
    // resource, and the header spent about 110dp of it on three things nobody
    // needs: the game's name, which you chose two taps ago and are currently
    // playing; the player count, which is the number of columns in the table
    // directly below it; and a round counter, which is the last number in the #
    // column. Every one of them was already on screen, printed larger, in the
    // thing you are actually looking at. The table gets the space instead.
    LiverySheet {
        when (live.shape) {
            Shape.LEDGER -> LedgerTable(live, Modifier.weight(1.3f))
            Shape.PER_PLAYER_SCORE -> RoundTable(
                live = live,
                modifier = Modifier.weight(1.5f).fillMaxWidth(),
                draft = draft,
                cursorPlayerId = typing?.playerId,
            )
            // Durak's table reads fine with zero rounds — columns and zero
            // totals — and needs no special empty state.
            else -> RoundTable(live, Modifier.weight(1.5f).fillMaxWidth())
        }

        VSpace(10.dp)

        when (live.shape) {
            Shape.PER_PLAYER_SCORE -> PerPlayerKeypad(
                active = active,
                current = typing,
                atLast = idx >= active.lastIndex,
                draft = draft,
                onDraft = { draft = it },
                onCommitRound = {
                    onRecordScores(active.associate { p -> p.playerId to (draft[p.playerId]?.toIntOrNull() ?: 0) })
                    draft = emptyMap()
                    cursor = 0
                },
                onAdvance = { cursor = idx + 1 },
                // Was 2.1 against a table of 1. The keys were already the
                // biggest thing on the screen by a distance and a key does not
                // get more pressable past a thumb's width, so the table takes
                // the difference.
                modifier = Modifier.weight(1.75f),
            )
            Shape.LOSER_ONLY -> LoserDock(active, onRecordLoser, Modifier.weight(1.3f))
            Shape.WINNER_ONLY -> WinnerDock(active, onRecordScores, Modifier.weight(1.3f))
            Shape.RANKING -> RankDock(active, onRecordScores, Modifier.weight(1.3f))
            Shape.LEDGER -> LedgerDock(live, active, onLedger, Modifier.weight(2.4f))
        }

        VSpace(10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sticker(
                onClick = onUndo,
                modifier = Modifier.weight(1f),
                variant = StickerVariant.CHIP,
                enabled = live.roundCount > 0 || live.ledger.isNotEmpty(),
            ) { StickerLabel("Undo") }
            Sticker(
                onClick = onEnd,
                modifier = Modifier.weight(1f),
                variant = StickerVariant.CHIP,
                departure = true,
            ) { StickerLabel("End session") }
        }
    }
}

// ---------------------------------------------------------------------------
// PER_PLAYER_SCORE — Gambio and friends
// ---------------------------------------------------------------------------

@Composable
private fun PerPlayerKeypad(
    active: List<Participant>,
    current: Participant?,
    atLast: Boolean,
    draft: Map<Long, String>,
    onDraft: (Map<Long, String>) -> Unit,
    onCommitRound: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Keypad(
        modifier = modifier,
        onDigit = { d ->
            current ?: return@Keypad
            val cur = draft[current.playerId].orEmpty()
            if (cur.replace("-", "").length < 4) onDraft(draft + (current.playerId to cur + d))
        },
        onNegate = {
            current ?: return@Keypad
            val cur = draft[current.playerId].orEmpty()
            onDraft(draft + (current.playerId to if (cur.startsWith("-")) cur.drop(1) else "-$cur"))
        },
        onBackspace = {
            current ?: return@Keypad
            onDraft(draft + (current.playerId to draft[current.playerId].orEmpty().dropLast(1)))
        },
        onCommit = { if (atLast) onCommitRound() else onAdvance() },
        commitLabel = if (atLast) "Add round" else "Next",
        commitEnabled = active.isNotEmpty(),
    )
}

// ---------------------------------------------------------------------------
// LOSER_ONLY — Durak. One tap is the entire interaction.
// ---------------------------------------------------------------------------

@Composable
private fun LoserDock(
    active: List<Participant>,
    onRecord: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Weighted, like the keypad on the other two screens. Left to wrap its own
    // content the dock took only what its buttons happened to need and handed
    // the entire rest of the sheet to the table — which for Durak is one mark
    // per round, so a fresh session was a full screen of blank card above four
    // small buttons. Now the dock claims a share and its buttons fill it: the
    // one tap this game consists of is the biggest target on the screen.
    Column(modifier) {
        MutedText("Who lost?")
        VSpace(8.dp)
        active.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { p ->
                    Sticker(
                        onClick = { onRecord(p.playerId) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        variant = StickerVariant.NORMAL,
                    ) { FittedStickerLabel(p.name, fraction = 0.42f) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Sticker(
            onClick = { onRecord(null) },
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.CHIP,
        ) { StickerLabel("Drawn round") }
    }
}

// ---------------------------------------------------------------------------
// WINNER_ONLY — Uno kept the short way. One tap, same as Durak's.
// ---------------------------------------------------------------------------

@Composable
private fun WinnerDock(
    active: List<Participant>,
    onRecord: (Map<Long, Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MutedText("Who won?")
        VSpace(8.dp)
        active.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { p ->
                    Sticker(
                        onClick = {
                            onRecord(Scoring.winnerPoints(p.playerId, active.map { it.playerId }))
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        variant = StickerVariant.NORMAL,
                    ) { FittedStickerLabel(p.name, fraction = 0.42f) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// RANKING — the order everybody went out in
// ---------------------------------------------------------------------------

/**
 * Tap the names in the order they went out. Each tap stamps a place; when one
 * name is left it takes the last place itself and the round lands — asking you
 * to confirm what only one person can possibly be would be a press for nothing,
 * every hand, all evening.
 *
 * A tapped name stays pressable, and pressing it takes it and everyone after it
 * back off the board. That is the only way to fix a mis-tap before the round
 * commits, and it costs no extra furniture on a dock that is already the
 * biggest thing on the screen.
 */
@Composable
private fun RankDock(
    active: List<Participant>,
    onRecord: (Map<Long, Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var order by remember(active.map { it.playerId }) { mutableStateOf(listOf<Long>()) }

    Column(modifier) {
        MutedText(
            if (order.isEmpty()) "Who went out, in order?"
            else "Then who? · ${order.size + 1} of ${active.size}",
        )
        VSpace(8.dp)
        active.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { p ->
                    val place = order.indexOf(p.playerId)
                    Sticker(
                        onClick = {
                            if (place >= 0) {
                                order = order.take(place)
                            } else {
                                val next = order + p.playerId
                                val left = active.map { it.playerId }.filter { it !in next }
                                if (left.size <= 1) {
                                    onRecord(Scoring.rankingPoints(next + left))
                                    order = emptyList()
                                } else {
                                    order = next
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        variant = if (place >= 0) StickerVariant.PICK_ON else StickerVariant.NORMAL,
                    ) {
                        // The place is set beside the name, not glued onto the
                        // front of it. As one string it was one label competing
                        // with itself for the width of the button, and the name
                        // is what you are checking you tapped.
                        if (place >= 0) {
                            Row(
                                Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DigitsText(
                                    "${place + 1}",
                                    color = Livery.Surface,
                                    fontSize = 26.sp,
                                    align = TextAlign.Start,
                                )
                                Spacer(Modifier.width(8.dp))
                                FittedStickerLabel(
                                    p.name,
                                    Modifier.weight(1f),
                                    fraction = 0.42f,
                                )
                            }
                        } else {
                            FittedStickerLabel(p.name, fraction = 0.42f)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// LEDGER — Poker
// ---------------------------------------------------------------------------

@Composable
private fun LedgerTable(live: LiveSession, modifier: Modifier = Modifier) {
    val net = Scoring.ledgerNet(live.participants, live.ledger)
    val sym = live.game.currencySymbol
    LiveryCard(modifier.fillMaxWidth()) {
        // fill = true: the names take the card and the recount banner sits on
        // its bottom edge. With fill = false a three-handed table left two
        // thirds of the card blank below the last player.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            live.participants.forEach { p ->
                val mine = live.ledger.filter { it.playerId == p.playerId }
                val inn = mine.filter { it.kind == LedgerKind.BUY_IN }.sumOf { it.amountCents }
                val out = mine.filter { it.kind == LedgerKind.CASH_OUT }.sumOf { it.amountCents }
                // Compact rows: a poker table is four to eight people, and all
                // of them have to be on screen at once to be worth anything.
                // The name and the net are what you read across the table; in
                // and out are the working. They were 15sp and 16sp — smaller
                // than the muted caption between them deserved to be, on the
                // one screen that is about money.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    HeadingText(p.name, fontSize = 24.sp, modifier = Modifier.weight(1f))
                    MutedText("in ${formatMoney(inn, sym)}  out ${formatMoney(out, sym)}")
                    DigitsText(
                        formatMoney(net[p.playerId] ?: 0, sym),
                        fontSize = 26.sp,
                        color = if ((net[p.playerId] ?: 0) >= 0) Livery.Ink else Livery.Red,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        // Pinned, never scrolled away. Catching a miscount while everyone is
        // still at the table is the whole reason to keep a ledger rather than
        // trust memory, so it cannot be the thing that falls below the fold.
        if (live.imbalance != 0 && live.ledger.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Livery.Yellow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                MetaText("Off by ${formatMoney(live.imbalance, sym)} — recount")
            }
        }
    }
}

@Composable
private fun LedgerDock(
    live: LiveSession,
    active: List<Participant>,
    onLedger: (Long, LedgerKind, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var who by remember(live.session.id) { mutableStateOf<Long?>(null) }
    var amount by remember(live.session.id) { mutableStateOf("") }
    val sym = live.game.currencySymbol
    val cents = amount.toIntOrNull()?.times(100) ?: live.game.defaultBuyInCents
    val picked = active.firstOrNull { it.playerId == who }

    Column(modifier) {
        // Who the money belongs to, as full-size buttons on their own rows.
        // They used to be little chips crushed into one line beside the
        // amount, which at a five-handed table left each name about a
        // fingernail wide — on the one screen where picking the wrong person
        // costs somebody real money.
        // Three to a row: a poker table is three to eight people, and at three
        // to a row that is one or two rows either way — two-up would have cost
        // three rows at six-handed and left the keypad squashed.
        active.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { p ->
                    Sticker(
                        onClick = { who = p.playerId },
                        modifier = Modifier.weight(1f),
                        variant = if (who == p.playerId) StickerVariant.CHIP_ON
                        else StickerVariant.CHIP,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 14.dp),
                    ) { StickerLabel(p.name) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // The amount, said out loud. It is the thing you are typing, so it is
        // the biggest thing on the dock rather than a caption at the end of a
        // row of names — and it says whose it is, so a mis-tap on a name is
        // visible before the money moves.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MutedText(picked?.name?.uppercase() ?: "Pick a player")
            DigitsText(
                text = "$sym${amount.ifEmpty { (live.game.defaultBuyInCents / 100).toString() }}_",
                fontSize = 34.sp,
                color = when {
                    picked == null -> Livery.GhostText
                    amount.isEmpty() -> Livery.Muted
                    else -> Livery.Ink
                },
            )
        }

        Keypad(
            modifier = Modifier.weight(1f),
            onDigit = { d -> if (amount.length < 5) amount += d },
            // No minus in a ledger — money in and money out are two buttons,
            // not a sign. The key becomes the one you actually want typing
            // whole euros: a second zero.
            onNegate = { if (amount.isNotEmpty() && amount.length < 4) amount += "00" },
            negateLabel = "00",
            allowNegative = amount.isNotEmpty(),
            onBackspace = { amount = amount.dropLast(1) },
            onCommit = {
                who?.let { onLedger(it, LedgerKind.BUY_IN, cents); amount = "" }
            },
            commitLabel = "Buy-in",
            commitEnabled = who != null,
            secondaryLabel = "Cash out",
            onSecondary = {
                who?.let { onLedger(it, LedgerKind.CASH_OUT, cents); amount = "" }
            },
            secondaryEnabled = who != null && amount.isNotEmpty(),
        )
    }
}
