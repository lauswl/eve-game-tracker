package card.game.tracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import card.game.tracker.data.CatalogEntry
import card.game.tracker.data.GameCatalog
import card.game.tracker.data.ScoringMode
import card.game.tracker.ui.components.LiverySheet
import card.game.tracker.ui.components.MetaText
import card.game.tracker.ui.components.ScrollTail
import card.game.tracker.ui.components.Sticker
import card.game.tracker.ui.components.StickerLabel
import card.game.tracker.ui.components.StickerVariant
import card.game.tracker.ui.components.TitleText
import card.game.tracker.ui.components.VSpace

/**
 * The first screen of a fresh install: which games do you play?
 *
 * Nothing is seeded before this. Guessing four games on the user's behalf meant
 * a first run that opened onto somebody else's card table, and the first job of
 * the app was tidying it — three games to switch off before you could use the
 * one you wanted. Asking costs one screen, once.
 *
 * Nothing is written until START, so every tap here is free and reversible. A
 * game the library has never heard of is typed into the search box and picked up
 * by [CustomGameCard], which is the same path the Add screen uses later.
 */
@Composable
fun OnboardingScreen(onStart: (List<CatalogEntry>) -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current

    // Custom games live here until START, exactly like the picked ones — they
    // are CatalogEntry values that simply are not in the catalogue.
    val invented = remember { mutableStateListOf<CatalogEntry>() }
    val picked = remember { mutableStateListOf<String>() }

    // How the games that have a choice are being kept, by key. Held beside the
    // picks rather than folded into a modified entry, because an entry with a
    // mode on it has the same key as the catalogue's — both would show in the
    // list and the same game would be sitting there twice.
    val chosen = remember { mutableStateMapOf<String, ScoringMode>() }
    var asking by remember { mutableStateOf<CatalogEntry?>(null) }

    val everything = remember(invented.size) { GameCatalog.all + invented }
    val results = remember(query, everything) {
        everything.filter { it.matches(query) }.sortedBy { it.name.lowercase() }
    }
    val nothingMatches = query.isNotBlank() &&
        results.none { it.name.equals(query.trim(), ignoreCase = true) }

    asking?.let { entry ->
        ScoringModeSheet(
            entry = entry,
            onPick = { mode ->
                chosen[entry.key] = mode
                if (entry.key !in picked) picked += entry.key
                asking = null
            },
            onBack = { asking = null },
        )
        return
    }

    LiverySheet {
        TitleText("Your games", fontSize = 44.sp)
        MetaText(
            if (picked.isEmpty()) "Pick what you play" else "${picked.size} picked",
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
                GameRow(
                    entry = entry,
                    picked = entry.key in picked,
                    departure = entry.key !in picked && entry.modes.isNotEmpty(),
                    onClick = {
                        when {
                            entry.key in picked -> {
                                picked.remove(entry.key)
                                chosen.remove(entry.key)
                            }
                            entry.modes.isNotEmpty() -> asking = entry
                            else -> picked.add(entry.key)
                        }
                    },
                )
            }
            if (nothingMatches) item(key = "custom") {
                CustomGameCard(
                    name = query,
                    departure = true,
                    onCreate = { entry ->
                        ime?.hide(); focus.clearFocus()
                        if (invented.none { it.key == entry.key }) invented += entry
                        if (entry.key !in picked) picked += entry.key
                        query = ""
                    },
                )
            }
            item(key = "tail") { ScrollTail() }
        }

        VSpace(12.dp)
        Sticker(
            // Reads the whole library, not what is on screen: a search typed
            // after picking must not quietly drop the picks it scrolled past.
            onClick = {
                onStart(
                    everything.filter { it.key in picked }
                        .map { e -> chosen[e.key]?.let(e::withMode) ?: e }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            variant = StickerVariant.PRIMARY,
            enabled = picked.isNotEmpty(),
            instant = false,
            departure = true,
        ) { StickerLabel(if (picked.isEmpty()) "Pick a game" else "Start") }
    }
}
