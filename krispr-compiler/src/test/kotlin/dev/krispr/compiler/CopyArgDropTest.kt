package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** COPY_ARG_DROP: `s.copy(a = 1, b = 2)` leaves out one argument, so that property keeps the copied value. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopyArgDropTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            data class S(val a: Int = 1, val b: String = "x", val c: Boolean = false)
            fun update(n: Int): String = S(a = 5, b = "w").copy(b = "y", a = n).toString()
            fun same(s: S): S = s.copy(a = s.a, c = true)
            fun next(calls: IntArray): Int { calls[0]++; return calls[0] }
            fun counted(calls: IntArray): String = S().copy(a = next(calls), b = "n" + calls[0]).toString() + calls[0]
            fun mapped(xs: List<Int>): String = S().copy(b = xs.map { it * 2 }.joinToString(), c = xs.isEmpty()).toString()
            class Plain(val a: Int) { fun copy(a: Int = 0) = Plain(a) }
            fun plain(): Int = Plain(1).copy(a = 2).a
            """.trimIndent(),
            operators = listOf("COPY_ARG_DROP"),
        )
    }

    private fun drops(line: Int): List<ManifestEntry> = compiled.mutantsOf("COPY_ARG_DROP").filter { it.line == line }

    @Test
    fun eachArgumentIsLeftOutInTurn() {
        val mutants = drops(2)
        assertEquals(
            listOf(
                "S(a = 5, b = \"w\").copy(b = \"y\", a = n) → S(a = 5, b = \"w\").copy(a = n)",
                "S(a = 5, b = \"w\").copy(b = \"y\", a = n) → S(a = 5, b = \"w\").copy(b = \"y\")",
            ),
            mutants.map { it.description }.sorted(),
        )
        assertEquals("S(a=3, b=y, c=false)", compiled.call("update", 3))
        val withoutB = mutants.single { it.description.endsWith("copy(a = n)") }
        val withoutA = mutants.single { it.description.endsWith("copy(b = \"y\")") }
        assertEquals("S(a=3, b=w, c=false)", compiled.call("update", 3, activeId = withoutB.id))
        assertEquals("S(a=5, b=y, c=false)", compiled.call("update", 3, activeId = withoutA.id))
    }

    @Test
    fun anArgumentThatPassesTheSameValueBackIsLeftAlone() {
        assertEquals(listOf("s.copy(a = s.a, c = true) → s.copy(a = s.a)"), drops(3).map { it.description })
    }

    @Test
    fun argumentsAreEvaluatedOnceInOrderEitherWay() {
        assertEquals("S(a=1, b=n1, c=false)1", compiled.call("counted", IntArray(1)))
        for (mutant in drops(5)) assertEquals(1, (compiled.call("counted", IntArray(1), activeId = mutant.id) as String).last().digitToInt())
        val withoutA = drops(5).single { it.description.endsWith("copy(b = \"n\" + calls[0])") }
        assertEquals("S(a=1, b=n1, c=false)1", compiled.call("counted", IntArray(1), activeId = withoutA.id))
    }

    @Test
    fun lambdaArgumentsAreCopiedIntoEachVariant() {
        val withoutC = drops(6).single { it.description.endsWith("copy(b = xs.map { it * 2 }.joinToString())") }
        assertEquals("S(a=1, b=2, 4, c=false)", compiled.call("mapped", listOf(1, 2), activeId = withoutC.id))
        assertEquals("S(a=1, b=, c=true)", compiled.call("mapped", emptyList<Int>()))
    }

    @Test
    fun onlyDataClassCopies() {
        assertEquals(emptyList<ManifestEntry>(), drops(8))
        assertEquals(2, compiled.call("plain"))
    }
}
