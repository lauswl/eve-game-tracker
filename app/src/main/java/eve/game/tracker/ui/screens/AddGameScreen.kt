package eve.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eve.game.tracker.data.CatalogEntry
import eve.game.tracker.data.GameCatalog
import eve.game.tracker.ui.components.LiveryCard
import eve.game.tracker.ui.components.LiverySheet
import eve.game.tracker.ui.components.MetaText
import eve.game.tracker.ui.components.MutedText
import eve.game.tracker.ui.components.ScrollTail
import eve.game.tracker.ui.components.Sticker
import eve.game.tracker.ui.components.StickerLabel
import eve.game.tracker.ui.components.StickerVariant
import eve.game.tracker.ui.components.TitleText
import eve.game.tracker.ui.components.VSpace

/**
 * The game library. Everything here is a preset over the three shapes, so
 * adding a game costs nothing but a row — and anything the preset gets wrong for
 * your table is editable in Setup afterwards.
 *
 * A game that is not in the library is typed into the search box: when nothing
 * matches, the same [CustomGameCard] the onboarding screen uses appears with the
 * name already filled in.
 */
@Composable
fun AddGameScreen(
    alreadyAdded: Set<String>,
    onAdd: (CatalogEntry) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<CatalogEntry?>(null) }
    val focus = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current
    val results = remember(query, alreadyAdded) { GameCatalog.search(query, alreadyAdded) }
    val nothingMatches = query.isNotBlank() &&
        results.none { it.name.equals(query.trim(), ignoreCase = true) }

    // Counted, not subtracted. `all.size - alreadyAdded.size` goes NEGATIVE the
    // moment you invent a custom game: its key is in alreadyAdded and was never
    // in the library, so the screen announced "-1 GAMES".
    val left = remember(alreadyAdded) { GameCatalog.all.count { it.key !in alreadyAdded } }

    // Uno and Durak are scored two or three ways and the app is not entitled to
    // a guess, so they ask before they land. Everything else goes straight on.
    asking?.let { entry ->
        ScoringModeSheet(
            entry = entry,
            onPick = { asking = null; onAdd(entry.withMode(it)) },
            onBack = { asking = null },
        )
        return
    }

    LiverySheet {
        TitleText("Add", fontSize = 46.sp)
        MetaText(
            if (left == 1) "1 game left in the library" else "$left games left in the library",
            Modifier.padding(top = 8.dp),
        )
        VSpace(12.dp)

        GameSearchField(
            query = query,
            onQuery = { query = it },
            onDone = { ime?.hide(); focus.clearFocus() },
        )
        VSpace(12.dp)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results, key = { it.key }) { entry ->
                // The whole row is the button. A word saying "ADD" beside it was
                // labelling the only thing the row could possibly do.
                GameRow(
                    entry = entry,
                    departure = true,
                    onClick = {
                        ime?.hide(); focus.clearFocus()
                        if (entry.modes.isEmpty()) onAdd(entry) else asking = entry
                    },
                )
            }
            if (nothingMatches) item(key = "custom") {
                CustomGameCard(
                    name = query,
                    departure = true,
                    onCreate = { ime?.hide(); focus.clearFocus(); onAdd(it); query = "" },
                )
            }
            if (results.isEmpty() && query.isBlank()) item(key = "empty") {
                LiveryCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        MutedText("Everything is already on your home screen")
                        MetaText(
                            "Type a name to invent one",
                            Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item(key = "tail") { ScrollTail() }
        }

        VSpace(12.dp)
        // The way off this screen, so it is sized like a button and not like a
        // chip. As a CHIP it was 19sp of small caps across the whole width of
        // the sheet — the widest control on screen and the quietest thing on it.
        Sticker(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.NORMAL,
            departure = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        ) { StickerLabel("Done") }
    }
}
