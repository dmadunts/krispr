package dev.krispr.playground.feed

import java.security.MessageDigest

/**
 * Signs a feed page by stretching a key over its posts with iterated SHA-256 over a 64 KB block. The work
 * happens in the JDK's digest, which Krispr does not instrument, so a signature costs about the same few hundred
 * milliseconds in the recording run as in a mutant's run, and slows down in proportion to host load.
 */
class FeedSigner(private val rounds: Int = 3_000) {
    fun sign(posts: List<Post>, key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val page = (key + "|" + posts.joinToString(",") { "${it.id}:${it.likes}" }).toByteArray()
        val block = ByteArray(BLOCK_BYTES) { i -> page[i % page.size] }
        var state = digest.digest(page)
        repeat(rounds) {
            digest.update(state)
            digest.update(block)
            state = digest.digest()
        }
        return state.take(SIGNATURE_BYTES).joinToString("") { "%02x".format(it) }
    }

    fun verify(posts: List<Post>, key: String, signature: String): Boolean =
        signature.length == SIGNATURE_BYTES * 2 && sign(posts, key) == signature

    companion object {
        const val SIGNATURE_BYTES = 8
        const val BLOCK_BYTES = 65_536
    }
}
