package card.game.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import card.game.tracker.ui.DesignLab
import card.game.tracker.ui.components.ClickSound
import card.game.tracker.ui.components.LiveryShell
import card.game.tracker.ui.components.LiverySheet
import card.game.tracker.ui.components.Stage
import card.game.tracker.ui.components.MetaText
import card.game.tracker.ui.screens.AddGameScreen
import card.game.tracker.ui.screens.HomeBody
import card.game.tracker.ui.screens.OnboardingScreen
import card.game.tracker.ui.screens.PlayerSelectScreen
import card.game.tracker.ui.screens.ScoringScreen
import card.game.tracker.ui.screens.SettingsBody
import card.game.tracker.ui.screens.StatsBody
import card.game.tracker.ui.screens.SummaryScreen
import card.game.tracker.ui.theme.Livery
import card.game.tracker.vm.Route
import card.game.tracker.vm.TrackerViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        goFullscreen()
        DesignLab.readFrom(intent)
        setContent { App() }
        // SoundPool construction and sample I/O do not belong on the first
        // frame's critical path. Prime shortly after the UI is visible; the
        // first realistic touch still arrives well after this on cold launch.
        lifecycleScope.launch {
            delay(500)
            ClickSound.prime(applicationContext)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back after the IME shows or the user swipes them in;
        // re-hide them so the sheet is never cropped mid-session.
        if (hasFocus) goFullscreen()
    }

    /**
     * The whole screen is the printed sheet — no status bar, no gesture pill.
     * A card table does not need the time and a phone chrome strip breaks the
     * trim keyline. Swiping from an edge still reveals the bars transiently.
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun App(vm: TrackerViewModel = viewModel(factory = TrackerViewModel.Factory)) {
    val route by vm.route.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    // Player selection is the common destination from Home. Keeping this one
    // cheap query warm avoids an empty intermediate frame after a game press.
    val players by vm.players.collectAsStateWithLifecycle()
    val lastSession by vm.lastSession.collectAsStateWithLifecycle()

    val tab = when (route) {
        Route.Stats -> 1
        Route.Settings -> 2
        else -> 0
    }
    val onTab: (Int) -> Unit = {
        when (it) {
            0 -> vm.goHome()
            1 -> vm.goStats()
            else -> vm.goSettings()
        }
    }

    BackHandler(enabled = route !is Route.Home) { vm.back() }

    Box(Modifier.fillMaxSize().background(Livery.Paper)) {
            // The three tabbed screens share one shell, hoisted above the
            // route so a tab press swaps only the body. Rebuilding the sheet,
            // its trim and the bar on every switch is what made the tabs feel
            // like they were loading something, and it threw away the very
            // sticker that had just been pressed before its red could show.
            val tabbed = route is Route.Home || route is Route.Stats || route is Route.Settings
            // Keep the shell alive behind detail screens too. Back from player
            // selection or a summary is now a placement, not a full rebuild of
            // Home, its measured type, and every game tile.
            Stage(tabbed) {
                LiveryShell(
                    tabs = listOf("Home", "Stats", "Setup"),
                    selected = tab,
                    onSelect = onTab,
                ) {
                    // One Stage per tab, and a Stage you have visited is never
                    // taken down again — see Stage. This is what makes the second
                    // and every later press of a tab a placement rather than a
                    // rebuild of the whole screen.
                    //
                    // They OVERLAP in a Box. Stacked in the shell's Column the
                    // first one would take the whole height and the other two
                    // would be laid out into nothing at all.
                    Box(Modifier.fillMaxSize()) {
                        Stage(tab == 0) {
                            HomeBody(
                                games = games,
                                lastSession = lastSession,
                                onPickGame = vm::pickPlayersFor,
                            )
                        }
                        Stage(tab == 1) {
                            // Leaderboards are the only expensive read in the app.
                            // Do not scan every historical row during cold launch;
                            // the first Stats visit starts it and Stage keeps it hot.
                            val stats by vm.stats.collectAsStateWithLifecycle()
                            StatsBody(stats)
                        }
                        Stage(tab == 2) {
                            val context = LocalContext.current
                            val scope = rememberCoroutineScope()
                            val sessionCounts by vm.sessionCounts.collectAsStateWithLifecycle()
                            val hasStoredData by vm.hasStoredData.collectAsStateWithLifecycle()
                            SettingsBody(
                                // The games you have, not every row in the table.
                                // A removed game is still on file — that is the
                                // whole point — but Setup is a list of your games,
                                // and one that also showed the ones you got rid of
                                // would be a list you can never finish tidying.
                                games = games,
                                players = players,
                                onRemoveGame = vm::removeGame,
                                onUpdateGame = vm::updateGame,
                                onChangeScoring = vm::changeScoring,
                                onDeleteAll = vm::deleteAllData,
                                onExport = {
                                    scope.launch { shareExport(context, vm.exportJson()) }
                                },
                                onAddGame = vm::goAddGame,
                                sessionCounts = sessionCounts,
                                hasStoredData = hasStoredData,
                                soundOn = ClickSound.enabled,
                                onSound = ClickSound::enable,
                            )
                        }
                    }
                }
            }

            when (val r = route) {
                is Route.Home, is Route.Stats, is Route.Settings -> Unit

                is Route.PlayerSelect -> {
                    val game = games.firstOrNull { it.id == r.gameId }
                    if (game == null) LoadingSheet() else PlayerSelectScreen(
                        game = game,
                        players = players,
                        loadLastLineup = { vm.lastLineup(game.id) },
                        onStart = { ids -> vm.startSession(game.id, ids) },
                        onAddPlayer = { vm.addPlayer(it) },
                        onBack = vm::goHome,
                    )
                }

                is Route.Scoring -> {
                    val live by vm.live.collectAsStateWithLifecycle()
                    val s = live
                    if (s == null) LoadingSheet() else ScoringScreen(
                        live = s,
                        onRecordScores = vm::recordScores,
                        onRecordLoser = vm::recordLoser,
                        onLedger = vm::addLedger,
                        onUndo = vm::undo,
                        onJoin = vm::joinSession,
                        onLeave = vm::leaveSession,
                        onEnd = vm::endSession,
                    )
                }

                is Route.Summary -> {
                    val live by vm.live.collectAsStateWithLifecycle()
                    val s = live
                    if (s == null) LoadingSheet() else SummaryScreen(
                        live = s,
                        onDone = vm::goHome,
                    )
                }

                // Only the games you actually have count as added. A removed one
                // has to be offered again or it is unreachable forever — its row
                // is still in the table, so nothing else can ever claim its key.
                is Route.AddGame -> AddGameScreen(
                    alreadyAdded = games.map { it.gameKey }.toSet(),
                    onAdd = vm::addFromCatalog,
                    onBack = vm::goSettings,
                )

                is Route.Onboarding -> OnboardingScreen(onStart = vm::finishOnboarding)
            }
    }
}

@Composable
private fun LoadingSheet() {
    LiverySheet { MetaText("Loading") }
}

/** Hands the export to the system share sheet — the user picks where it goes. */
private suspend fun shareExport(context: android.content.Context, json: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "gametracker-export.json")
        file.writeText(json)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export game data"))
}
