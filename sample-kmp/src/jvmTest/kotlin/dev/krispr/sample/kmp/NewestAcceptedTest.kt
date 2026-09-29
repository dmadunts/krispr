package dev.krispr.sample.kmp

import kotlin.test.Test
import kotlin.test.assertEquals

class NewestAcceptedTest {
    @Test
    fun picksTheNewestInRange() {
        val lines = listOf("1.0.0", " 1.4.2", "2.0.0", "junk", "1.3.9")
        assertEquals(Version(1, 4, 2), newestAccepted(Version(1, 1, 0), lines))
        assertEquals("jvm", platformTag())
    }
}
