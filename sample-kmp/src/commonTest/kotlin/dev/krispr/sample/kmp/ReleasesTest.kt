package dev.krispr.sample.kmp

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleasesTest {
    @Test
    fun classifiesChannels() {
        assertEquals(Channel.STABLE, channelOf(Version(1, 0, 0)))
        assertEquals(Channel.PREVIEW, channelOf(Version(1, 0, 0, "rc1")))
        assertEquals(Channel.PREVIEW, channelOf(Version(0, 9, 0)))
    }

    // Weak on purpose: never checks a release exactly at the download threshold, or the preview channel.
    @Test
    fun suggestsPopularStableReleases() {
        assertTrue(suggestable(Release(Version(1, 2, 0), 500), Channel.STABLE, 100))
        assertFalse(suggestable(Release(Version(1, 2, 0), 5), Channel.STABLE, 100))
        assertFalse(suggestable(Release(Version(1, 2, 0), 500, yanked = true), Channel.STABLE, 100))
    }

    @Test
    fun streamsSuggestions() = runTest {
        val lines = flowOf("1.0.0 900", "1.1.0-rc1 900", "junk", "1.2.0 12", " 2.0.0 150 ")
        assertEquals(listOf(Version(1, 0, 0), Version(2, 0, 0)), suggestions(lines, Channel.STABLE).toList())
    }
}
