package dev.krispr.playground.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Slow but legitimate: each signature hashes about 200 MB with SHA-256, over a second in Krispr's C1-only forks, a few hundred milliseconds.
class FeedSignerTest {
    private val signer = FeedSigner()
    private val page = listOf(Post(1, 3, 150, 1, 120), Post(2, 8, 10, 30, 60))

    @Test
    fun signatureIsStable() {
        assertEquals("f893f5b8132de09f", signer.sign(page, "k1"))
    }

    @Test
    fun verifiesItsOwnSignature() {
        assertTrue(signer.verify(page, "k1", "f893f5b8132de09f"))
        assertFalse(signer.verify(page, "k1", "f893f5b8132de09"))
    }

    @Test
    fun rejectsATamperedPage() {
        assertFalse(signer.verify(page.map { it.copy(likes = it.likes + 1) }, "k1", "f893f5b8132de09f"))
    }
}
