package card.game.tracker.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import card.game.tracker.data.GameDefEntity
import card.game.tracker.data.LiveSession
import card.game.tracker.data.PlayerEntity
import card.game.tracker.data.Repository
import card.game.tracker.data.StatsSnapshot
import card.game.tracker.data.TrackerDb
import card.game.tracker.domain.LedgerKind

/** Where the app is. A small explicit stack beats a navigation graph here. */
sealed interface Route {
    data object Home : Route
    data object Stats : Route
    data object Settings : Route
    data object AddGame : Route
    /** First run: nothing is on file and nobody has said what they play. */
    data object Onboarding : Route
    data class PlayerSelect(val gameId: Long) : Route
    data class Scoring(val sessionId: Long) : Route
    data class Summary(val sessionId: Long) : Route
}

class TrackerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(TrackerDb.get(app))

    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route.asStateFlow()

    val games: StateFlow<List<GameDefEntity>> = repo.enabledGames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val players: StateFlow<List<PlayerEntity>> = repo.activePlayers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastSession: StateFlow<LiveSession?> = repo.lastFinishedSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** What Setup needs to know before it decides whether to warn about anything. */
    val sessionCounts: StateFlow<Map<Long, Int>> = repo.sessionCountsByGame()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val hasStoredData: StateFlow<Boolean> = repo.hasStoredData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Built on first Stats visit, then retained while the visited tab lives. */
    val stats: StateFlow<StatsSnapshot?> = repo.statsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _liveSessionId = MutableStateFlow<Long?>(null)

    /**
     * The session on the scoring screen.
     *
     * flatMapLatest, NOT a nested collect: collecting the inner session flow
     * inside `collect` never returns, so every id after the first would be
     * ignored and the app would show the first session it ever opened for the
     * rest of its life.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val live: StateFlow<LiveSession?> = _liveSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.liveSession(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            if (card.game.tracker.ui.DesignLab.demo) repo.seedDemoData()
            // an unfinished session survives the app being killed mid-round
            val open = repo.openSession()
            when {
                open != null -> {
                    _liveSessionId.value = open.id
                    _route.value = Route.Scoring(open.id)
                }
                // Checked once, here, rather than watching the games flow: an
                // empty list is also what that flow says for the first frame
                // after launch, and onboarding must not flash up on a phone
                // that has four years of Skat on it.
                !repo.hasAnyGames() -> _route.value = Route.Onboarding
            }
        }
        viewModelScope.launch {
            // Populate the expensive leaderboard after launch, when Home is
            // idle. The retained StateFlow then makes the first Stats visit an
            // immediate draw without putting a history scan on startup's path.
            delay(1_000)
            if (_route.value is Route.Home) stats.filterNotNull().first()
        }
    }

    // ------------------------------------------------------------ navigation

    fun goHome() { _route.value = Route.Home }
    fun goStats() { _route.value = Route.Stats }
    fun goSettings() { _route.value = Route.Settings }
    fun goAddGame() { _route.value = Route.AddGame }

    fun addFromCatalog(entry: card.game.tracker.data.CatalogEntry) = viewModelScope.launch {
        repo.addFromCatalog(entry)
    }

    /** Onboarding's one write: everything that was picked, then straight home. */
    fun finishOnboarding(entries: List<card.game.tracker.data.CatalogEntry>) =
        viewModelScope.launch {
            repo.addAllFromCatalog(entries)
            _route.value = Route.Home
        }

    fun pickPlayersFor(gameId: Long) { _route.value = Route.PlayerSelect(gameId) }

    fun back() {
        _route.value = when (val r = _route.value) {
            is Route.AddGame -> Route.Settings
            is Route.Onboarding -> r      // there is nowhere behind the first screen
            is Route.PlayerSelect -> Route.Home
            is Route.Summary -> Route.Home
            is Route.Scoring -> r          // never lose a live session to a back press
            else -> Route.Home
        }
    }

    // --------------------------------------------------------------- session

    suspend fun lastLineup(gameId: Long): List<Long> = repo.lastLineup(gameId)

    fun startSession(gameId: Long, playerIds: List<Long>) = viewModelScope.launch {
        if (playerIds.isEmpty()) return@launch
        val id = repo.startSession(gameId, playerIds)
        _liveSessionId.value = id
        _route.value = Route.Scoring(id)
    }

    fun endSession() = viewModelScope.launch {
        val id = _liveSessionId.value ?: return@launch
        repo.endSession(id)
        // keep the id: the summary screen renders this very session. It is
        // finished, so openSession() will not resurrect it on next launch.
        _route.value = Route.Summary(id)
    }

    /** Re-opens a finished session read-only for the summary screen. */
    fun showSummary(sessionId: Long) {
        _liveSessionId.value = sessionId
        _route.value = Route.Summary(sessionId)
    }

    fun addPlayer(name: String, andJoinSession: Boolean = false) = viewModelScope.launch {
        val pid = repo.addPlayer(name)
        if (andJoinSession) _liveSessionId.value?.let { repo.addPlayerToSession(it, pid) }
    }

    fun joinSession(playerId: Long) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.addPlayerToSession(it, playerId) }
    }

    fun leaveSession(playerId: Long) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.removePlayerFromSession(it, playerId) }
    }

    // ---------------------------------------------------------------- rounds

    fun recordScores(scores: Map<Long, Int>) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.recordScoreRound(it, scores) }
    }

    fun recordLoser(loserId: Long?) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.recordLoserRound(it, loserId) }
    }

    fun undo() = viewModelScope.launch {
        val id = _liveSessionId.value ?: return@launch
        val s = live.value
        if (s?.game?.shape == card.game.tracker.domain.Shape.LEDGER) {
            repo.undoLastLedger(id)
        } else {
            repo.undoLastRound(id)
        }
    }

    fun correctScore(roundIndex: Int, playerId: Long, value: Int) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.correctScore(it, roundIndex, playerId, value) }
    }

    fun correctLoser(roundIndex: Int, loserId: Long?) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.correctLoser(it, roundIndex, loserId) }
    }

    // ---------------------------------------------------------------- ledger

    fun addLedger(playerId: Long, kind: LedgerKind, cents: Int) = viewModelScope.launch {
        _liveSessionId.value?.let { repo.addLedger(it, playerId, kind, cents) }
    }

    // -------------------------------------------------------- games / stats

    fun removeGame(def: GameDefEntity) = viewModelScope.launch { repo.removeGame(def) }

    fun updateGame(def: GameDefEntity) = viewModelScope.launch { repo.updateGame(def) }

    /** Setup has already shown the warning and been told yes. */
    fun changeScoring(def: GameDefEntity) = viewModelScope.launch { repo.changeScoring(def) }

    /**
     * Wipes the lot and goes back to the screen a fresh install opens on. The
     * live session id goes with it — it now points at a row that is not there.
     */
    fun deleteAllData() = viewModelScope.launch {
        repo.deleteEverything()
        _liveSessionId.value = null
        _route.value = Route.Onboarding
    }

    suspend fun exportJson(): String = repo.exportJson()

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return TrackerViewModel(app) as T
            }
        }
    }
}
