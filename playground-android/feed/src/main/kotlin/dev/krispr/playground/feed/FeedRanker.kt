package dev.krispr.playground.feed

data class Post(val id: Int, val author: Int, val likes: Int, val ageHours: Int, val words: Int)

/**
 * Ranks a feed by simulating how engagement spreads through a follower graph, averaged over many trials:
 * honest CPU work of tens of milliseconds per post, which slows down in proportion to host load.
 */
class FeedRanker(private val users: Int = 300, private val rounds: Int = 12, private val trials: Int = 20) {
    private val follows: Array<IntArray> = Array(users) { u -> IntArray(8) { k -> (u * 31 + k * 97 + k * k * 13) % users } }

    fun reach(post: Post): Int {
        val percent = spreadPercent(post)
        var total = 0L
        for (trial in 0 until trials) {
            var seed = post.id * 7919L + trial * 104729L
            var active = BooleanArray(users)
            active[post.author % users] = true
            var reached = 1
            repeat(rounds) {
                val next = active.copyOf()
                for (u in 0 until users) {
                    if (active[u]) continue
                    var seen = 0
                    for (f in follows[u]) if (active[f]) seen++
                    if (seen == 0) continue
                    seed = (seed * 6364136223846793005L + 1442695040888963407L)
                    val roll = ((seed ushr 33) % 100).toInt()
                    if (roll < percent * seen) {
                        next[u] = true
                        reached++
                    }
                }
                active = next
            }
            total += reached
        }
        return (total / trials).toInt()
    }

    fun spreadPercent(post: Post): Int = when {
        post.likes >= 300 -> 12
        post.likes >= 100 -> 8
        post.likes >= 10 -> 5
        else -> 2
    }

    fun score(post: Post): Double {
        val decay = 1.0 / (1 + post.ageHours / 6.0)
        val length = if (post.words < 20) 0.5 else if (post.words > 400) 0.8 else 1.0
        return reach(post) * decay * length + post.likes / 10.0
    }

    fun rank(posts: List<Post>): List<Int> = posts.map { it to score(it) }.sortedByDescending { it.second }.map { it.first.id }
}
