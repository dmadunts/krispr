package dev.krispr.sample

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaybackTest {
    @Test
    fun titlesSkipMissingIdsAfterAStartMarker() = runTest {
        val playback = Playback(backgroundScope, StandardTestDispatcher(testScheduler)) { it }
        assertEquals(listOf("(start)", "a", "b"), playback.titles(flowOf("a", null, "b")).toList())
    }

    @Test
    fun playPublishesAnEvent() = runTest {
        val playback = Playback(backgroundScope, StandardTestDispatcher(testScheduler)) { it }
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { playback.events.toList(seen) }
        playback.play("a")
        assertEquals("play:a", seen.first())
    }

    @Test
    fun title() = runTest {
        val playback = Playback(backgroundScope, StandardTestDispatcher(testScheduler)) { if (it == "x") error("gone") else it.uppercase() }
        assertEquals("A", playback.title("a"))
        assertNull(playback.title("x"))
    }

    @Test
    fun stopIsRecorded() = runTest {
        val playback = Playback(backgroundScope, StandardTestDispatcher(testScheduler)) { it }
        playback.stop()
        assertEquals(listOf("stop"), playback.history)
    }
}
