package dev.krispr.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ProbeTest {
    private data class Point(val x: Int, val y: Int)

    private enum class Color { RED }

    private class Service {
        var calls = 0
        override fun toString(): String = "Service(calls=${++calls})"
    }

    private class Exploding(val x: Int) {
        fun component1() = x
        fun copy() = this
        override fun toString(): String = error("boom")
    }

    @AfterEach
    fun tearDown() {
        Probe.out = null
        Probe.reset()
    }

    @Test
    fun rendersValuesWithoutSideEffects() {
        assertEquals("3" to false, Probe.render(3))
        assertEquals("null" to false, Probe.render(null))
        assertEquals("\"a\\nb\"" to false, Probe.render("a\nb"))
        assertEquals("'c'" to false, Probe.render('c'))
        assertEquals("RED" to false, Probe.render(Color.RED))
        assertEquals("Point(x=1, y=2)" to false, Probe.render(Point(1, 2)))
        assertEquals("[1, 2, \"x\"]" to false, Probe.render(listOf(1, 2, "x")))
        assertEquals("{\"a\"=1}" to false, Probe.render(mapOf("a" to 1)))
        assertEquals("[1, 2]" to false, Probe.render(intArrayOf(1, 2)))

        val service = Service()
        assertEquals("<Service>" to true, Probe.render(service))
        assertEquals(0, service.calls, "toString of an arbitrary type is never called")
    }

    @Test
    fun capsLongValues() {
        val (text, _) = Probe.render("x".repeat(500))
        assertTrue(text.length <= Probe.MAX_CHARS, text)
        assertTrue(text.endsWith("…"), text)
    }

    @Test
    fun aThrowingToStringIsRecordedAsItsType() {
        Probe.out = Files.createTempFile("probe", ".values").toFile()
        Probe.observe(Exploding(1))
        assertEquals(listOf("SO\t<Exploding>", "DO\t<Exploding>"), Probe.out!!.readLines())
    }

    @Test
    fun keepsTheFirstDistinctValuesAndTheSequence() {
        val file = Files.createTempFile("probe", ".values").toFile()
        Probe.out = file
        listOf(1, 1, 2, 3, 4, 5).forEach(Probe::observe)
        val lines = file.readLines()
        assertEquals(listOf("DV\t1", "DV\t2", "DV\t3"), lines.filter { it.startsWith("D") })
        assertEquals(1, lines.count { it.startsWith("M") })
        assertEquals(listOf("1", "1", "2", "3", "4", "5"), lines.filter { it.startsWith("S") }.map { it.substringAfter('\t') })
    }

    @Test
    fun onlyTheProbedMutantMatches() {
        val previous = Mutants.probeId
        Mutants.probeId = 7
        try {
            assertTrue(Mutants.isProbed(7))
            assertFalse(Mutants.isProbed(8))
        } finally {
            Mutants.probeId = previous
        }
    }
}
