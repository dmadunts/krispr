package dev.krispr.gradle

import dev.krispr.gradle.MutantStatus.KILLED
import dev.krispr.gradle.MutantStatus.NO_COVERAGE
import dev.krispr.gradle.MutantStatus.SURVIVED
import dev.krispr.gradle.MutantStatus.TIMED_OUT
import dev.krispr.gradle.MutantStatus.UNKNOWN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HistoryTest {
    private val hashes = mutableMapOf("[a]" to "A", "[b]" to "B")
    private fun classHash(selector: String) = hashes.getValue(selector)

    private fun entry(status: MutantStatus, killer: String? = null, tests: Map<String, String> = mapOf("[a]" to "A", "[b]" to "B")) =
        History.Entry("h1", status, killer, tests, listOf("ATest"), killer?.let { "a()" }, 12)

    private fun history(vararg entries: Pair<Int, History.Entry>) = History("s", entries.toMap())

    @Test
    fun aChangedDeclarationReusesNothing() {
        val history = history(1 to entry(KILLED, "[a]"), 2 to entry(SURVIVED))
        assertNull(history.reusable(1, "h2", listOf("[a]", "[b]"), ::classHash))
        assertNull(history.reusable(2, "h2", listOf("[a]", "[b]"), ::classHash))
        assertEquals("[a]", history.previousKiller(1))
    }

    @Test
    fun aKillHoldsWhileItsTestReachesTheMutantUnchanged() {
        val history = history(1 to entry(KILLED, "[a]"))
        assertNotNull(history.reusable(1, "h1", listOf("[a]"), ::classHash))
        // Other tests may change or go.
        hashes["[b]"] = "B2"
        assertNotNull(history.reusable(1, "h1", listOf("[a]", "[b]"), ::classHash))
        assertNull(history.reusable(1, "h1", listOf("[b]"), ::classHash))
        hashes["[a]"] = "A2"
        assertNull(history.reusable(1, "h1", listOf("[a]"), ::classHash))
    }

    @Test
    fun aSurvivorHoldsOnlyWhileAllItsTestsAreTheSame() {
        val history = history(1 to entry(SURVIVED), 2 to entry(TIMED_OUT))
        assertNotNull(history.reusable(1, "h1", listOf("[b]", "[a]"), ::classHash))
        assertNotNull(history.reusable(2, "h1", listOf("[a]", "[b]"), ::classHash))
        assertNull(history.reusable(1, "h1", listOf("[a]"), ::classHash))
        hashes["[b]"] = "B2"
        assertNull(history.reusable(1, "h1", listOf("[a]", "[b]"), ::classHash))
    }

    @Test
    fun otherVerdictsAreNeverReused() {
        val history = history(1 to entry(UNKNOWN), 2 to entry(NO_COVERAGE, tests = emptyMap()))
        assertNull(history.reusable(1, "h1", listOf("[a]", "[b]"), ::classHash))
        assertNull(history.reusable(2, "h1", emptyList(), ::classHash))
        assertFalse(UNKNOWN in History.KEPT)
    }

    @Test
    fun roundTripsAndIgnoresOtherSettings(@TempDir dir: File) {
        val file = File(dir, "history.json")
        history(7 to entry(KILLED, "[a]")).write(file)
        val read = History.read(file, "s")
        assertEquals("[a]", read?.previousKiller(7))
        assertEquals(mapOf("[a]" to "A", "[b]" to "B"), read?.entries?.get(7)?.tests)
        assertNull(History.read(file, "other"))
        file.writeText("{not json")
        assertNull(History.read(file, "s"))
    }

    @Test
    fun testClassHashesCoverNestedClassesAndChangeWithThem(@TempDir dir: File) {
        val pkg = File(dir, "com/example").apply { mkdirs() }
        File(pkg, "CartTest.class").writeBytes(byteArrayOf(1))
        File(pkg, "CartTest\$Nested.class").writeBytes(byteArrayOf(2))
        File(pkg, "CartTestHelper.class").writeBytes(byteArrayOf(3))
        val before = TestClassHashes(listOf(dir)).of("com.example.CartTest")
        assertEquals(before, TestClassHashes(listOf(dir)).of("com.example.CartTest\$Nested"))
        File(pkg, "CartTestHelper.class").writeBytes(byteArrayOf(4))
        assertEquals(before, TestClassHashes(listOf(dir)).of("com.example.CartTest"))
        File(pkg, "CartTest\$Nested.class").writeBytes(byteArrayOf(5))
        assertTrue(before != TestClassHashes(listOf(dir)).of("com.example.CartTest"))
        assertEquals("", TestClassHashes(listOf(dir)).of("com.example.Missing"))
    }

    @Test
    fun directHitsAreTestsNamedForTheMutatedClassOrFile() {
        assertTrue(KrisprRunTask.directHit("com.example.CartTest", "/src/Cart.kt", "Cart.total(Cart)"))
        assertTrue(KrisprRunTask.directHit("com.example.CartTests\$Nested", "/src/Shop.kt", "Cart.total(Cart)"))
        assertTrue(KrisprRunTask.directHit("com.example.TestCart", "/src/Cart.kt", "total()"))
        assertTrue(KrisprRunTask.directHit("com.example.CartSpec", "/src/Cart.kt", null))
        assertFalse(KrisprRunTask.directHit("com.example.CartTest", "/src/Shop.kt", "Shop.total(Shop)"))
        assertFalse(KrisprRunTask.directHit("com.example.Test", "/src/Test.kt", null))
    }
}
