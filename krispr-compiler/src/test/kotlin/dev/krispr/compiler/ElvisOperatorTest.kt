package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** ELVIS: `a ?: b → a!!`, so a null `a` throws instead of falling back. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElvisOperatorTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun country(c: String?): String = c?.uppercase() ?: "--"
            fun doubled(xs: List<Int>): Int { val x = xs.firstOrNull() ?: return -1; return x * 2 }
            fun must(x: String?): String = x ?: error("missing")
            fun thrown(x: String?): String = x ?: throw IllegalStateException()
            fun total(xs: List<String?>): Int { var n = 0; for (x in xs) { val s = x ?: continue; n += s.length }; return n }
            fun same(x: String?): String? = x ?: null
            fun name(x: String?, y: String?): String = x ?: y ?: "none"
            fun sum(xs: List<String?>): Int { var n = 0; for (x in xs) { val s = x ?: ""; n += s.length }; return n }
            fun next(calls: IntArray): String? { calls[0]++; return if (calls[0] > 5) null else "n" + calls[0] }
            fun counted(calls: IntArray): Int { val s = next(calls) ?: "none"; return calls[0] * 10 + s.length }
            """.trimIndent(),
        )
    }

    private fun elvis(line: Int): List<ManifestEntry> = compiled.mutantsOf("ELVIS").filter { it.line == line }

    @Test
    fun fallbackIsReplacedByANullCheck() {
        val id = elvis(1).single().id
        assertEquals("DE", compiled.call("country", "de", activeId = id))
        assertEquals("--", compiled.call("country", null))
        assertThrows(NullPointerException::class.java) { compiled.call("country", null, activeId = id) }
        assertEquals("c?.uppercase() ?: \"--\" → c?.uppercase()!!", elvis(1).single().description)
    }

    @Test
    fun earlyReturnsAndContinueCount() {
        assertEquals(-1, compiled.call("doubled", emptyList<Int>()))
        assertThrows(NullPointerException::class.java) { compiled.call("doubled", emptyList<Int>(), activeId = elvis(2).single().id) }
        assertEquals(2, compiled.call("total", listOf("ab", null)))
        assertThrows(NullPointerException::class.java) { compiled.call("total", listOf("ab", null), activeId = elvis(5).single().id) }
    }

    @Test
    fun loopsWithCompoundAssignmentsStillCompile() {
        // The elvis beside `n += …` (whose iinc shape the MATH schema breaks) in one loop body.
        assertEquals(3, compiled.call("sum", listOf("ab", null, "c")))
        assertThrows(NullPointerException::class.java) { compiled.call("sum", listOf("ab", null), activeId = elvis(8).single().id) }
    }

    @Test
    fun theLeftSideIsEvaluatedOnceEitherWay() {
        val id = elvis(10).single().id
        assertEquals(12, compiled.call("counted", IntArray(1)))
        assertEquals(12, compiled.call("counted", IntArray(1), activeId = id))
        assertEquals(64, compiled.call("counted", intArrayOf(5)))
        assertThrows(NullPointerException::class.java) { compiled.call("counted", intArrayOf(5), activeId = id) }
    }

    @Test
    fun throwingAndNullFallbacksAreLeftAlone() {
        assertEquals(emptyList<ManifestEntry>(), elvis(3) + elvis(4) + elvis(6))
        // `x ?: y ?: "none"` is `(x ?: y) ?: "none"`: one mutant per elvis.
        val inner = elvis(7).single { it.description == "x ?: y → x!!" }
        val outer = elvis(7).single { it.description == "x ?: y ?: \"none\" → x ?: y!!" }
        assertThrows(NullPointerException::class.java) { compiled.call("name", null, "y", activeId = inner.id) }
        assertEquals("y", compiled.call("name", null, "y", activeId = outer.id))
        assertThrows(NullPointerException::class.java) { compiled.call("name", null, null, activeId = outer.id) }
    }
}
