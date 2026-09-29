package dev.krispr.sample

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FLOW_EMIT: `tryEmit` statements removed; FLOW_OPERATOR: `filterNotNull`/`onStart` skipped;
 * COROUTINE_CONTEXT: `withContext(NonCancellable)` runs in the caller's context (a dispatcher-only
 * `withContext(io)` gets no mutant); CATCH_SWALLOW: a rethrown IllegalArgumentException swallowed (a
 * CancellationException rethrow gets none); LAUNCH_BODY: a launched body skipped.
 */
class Playback(private val scope: CoroutineScope, private val io: CoroutineDispatcher, private val load: suspend (String) -> String?) {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    val history = mutableListOf<String>()

    fun titles(ids: Flow<String?>): Flow<String> {
        val present = ids.filterNotNull()
        return present.onStart { emit("(start)") }
    }

    fun play(id: String) {
        _events.tryEmit("play:$id")
        // Weak: no test waits for the history write, so skipping the launched body goes unnoticed.
        scope.launch { history.add(id); _events.tryEmit("played:$id") }
    }

    suspend fun title(id: String): String? = withContext(io) {
        try {
            load(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Weak: no test loads a malformed id, so swallowing the rethrow goes unnoticed.
            throw e
        } catch (e: IllegalStateException) {
            null
        }
    }

    // Weak: no test stops a cancelled player, so dropping NonCancellable goes unnoticed.
    suspend fun stop() = withContext(NonCancellable) {
        history.add("stop")
    }
}
