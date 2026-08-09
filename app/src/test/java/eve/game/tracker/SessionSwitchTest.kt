package eve.game.tracker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the session-switching bug.
 *
 * The ViewModel used to select the live session with a nested collect:
 *
 *     flow { ids.collect { id -> source(id).collect { emit(it) } } }
 *
 * The inner collect on an endless source never returns, so the outer collect
 * never sees a second id — the app showed the first session it opened and
 * every later one silently did nothing. Only a real walkthrough caught it,
 * because each session works fine in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSwitchTest {

    /** Stands in for Repository.liveSession(id): endless, like a Room Flow. */
    private fun endlessSource(id: Long) = flow {
        emit("session-$id")
        awaitCancellation()
    }

    @Test
    fun `switching the selected id switches the emitted session`() = runTest {
        val selected = MutableStateFlow<Long?>(null)
        val seen = mutableListOf<String?>()

        val job = launch {
            selected
                .flatMapLatest { id -> if (id == null) flowOf(null) else endlessSource(id) }
                .toList(seen)
        }

        // runCurrent first so the collector is actually subscribed; a
        // StateFlow conflates anything set before that.
        runCurrent()
        selected.value = 1L
        runCurrent()
        selected.value = 2L
        runCurrent()
        job.cancel()

        assertEquals(listOf(null, "session-1", "session-2"), seen)
    }

    @Test
    fun `a nested collect would have stuck on the first id`() = runTest {
        val selected = MutableStateFlow<Long?>(null)
        val seen = mutableListOf<String?>()

        val job = launch {
            flow {
                selected.collect { id ->
                    if (id == null) emit(null) else endlessSource(id).collect { emit(it) }
                }
            }.toList(seen)
        }

        runCurrent()
        selected.value = 1L
        runCurrent()
        selected.value = 2L
        runCurrent()
        job.cancel()

        // documents the old behaviour: session 2 never arrives
        assertEquals(listOf(null, "session-1"), seen)
    }
}
