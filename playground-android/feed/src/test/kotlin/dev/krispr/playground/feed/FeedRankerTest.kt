package dev.krispr.playground.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Each test spends a few hundred milliseconds in FeedRanker once instrumented (the recording run's time is
// what Krispr's timeouts start from).
class FeedRankerTest {
    private val ranker = FeedRanker()
    private val posts = listOf(
        Post(1, 3, 150, 1, 120),
        Post(2, 8, 10, 30, 60),
        Post(3, 21, 90, 2, 10),
        Post(4, 55, 400, 48, 900),
        Post(5, 89, 0, 0, 200),
    )

    @Test
    fun reachGrowsWithLikes() {
        assertTrue(ranker.reach(posts[0]) > ranker.reach(posts[0].copy(likes = 1)))
    }

    @Test
    fun reachIsStable() {
        assertEquals(80, ranker.reach(posts[0]))
        assertEquals(145, ranker.reach(posts[3]))
    }

    @Test
    fun scores() {
        assertEquals(19.125, ranker.score(posts[2]), 1e-9)
        assertEquals(5.0, ranker.score(posts[4]), 1e-9)
    }

    @Test
    fun scoresDecayWithAge() {
        assertTrue(ranker.score(posts[1].copy(ageHours = 0)) > ranker.score(posts[1]))
    }

    @Test
    fun ranks() {
        val order = ranker.rank(posts.take(3))
        assertEquals(3, order.size)
        assertEquals(listOf(1, 3, 2), order)
    }
}
