package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** CONDITION_TRUE and CONDITION_FALSE: branch conditions forced one way, and `&&`/`||` clauses dropped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionOperatorTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun label(n: Int): String = if (n > 0) "pos" else "other"
            fun same(a: Int, b: Int): String = if (a == b) "same" else "diff"
            fun grade(n: Int): String = when { n >= 90 -> "A"; n >= 50 -> "B"; else -> "C" }
            fun both(a: Boolean, b: Boolean): Boolean = a && b
            fun either(a: Boolean, b: Boolean): Boolean = a || b
            fun len(s: String?): Int = if (s != null) s.length else -1
            fun blank(s: String?): Int = if (s.isNullOrEmpty()) 0 else s.length
            fun guarded(s: String?, n: Int): Int = if (s != null && n > 0) s.length else 0
            fun count(n: Int): Int { var i = 0; while (i < n) i++; return i }
            fun loops(xs: List<Int>): Int { var t = 0; for (x in xs) t += x; return t }
            fun kind(x: Any): String = when { x is Int -> "int"; else -> "any" }
            fun digit(x: Int): String = when (x) { 1 -> "one"; else -> "many" }
            fun always(): String = if (true) "a" else "b"
            val log = mutableListOf<String>()
            fun note(on: Boolean) { if (on) log.add("x") }
            fun wipe(on: Boolean) { if (on) log.clear() }
            fun Int.word(): String = when (this) { 1 -> "one"; else -> "many" }
            """.trimIndent(),
            operators = listOf("DEFAULTS"),
        )
    }

    private fun mutant(operator: String, line: Int, description: String? = null): ManifestEntry =
        compiled.mutants.singleOrNull { it.operator == operator && it.line == line && (description == null || it.description == description) }
            ?: error("no single $operator on line $line ($description): ${compiled.mutants.filter { it.line == line }}")

    private fun on(line: Int, operator: String) = compiled.mutants.filter { it.line == line && it.operator == operator }

    @Test
    fun ifConditionsAreForcedBothWaysInsteadOfNegated() {
        assertEquals("pos", compiled.call("label", -1, activeId = mutant("CONDITION_TRUE", 1).id))
        assertEquals("other", compiled.call("label", 1, activeId = mutant("CONDITION_FALSE", 1).id))
        assertEquals("other", compiled.call("label", -1, activeId = mutant("CONDITION_FALSE", 1).id))
        assertEquals("if (n > 0) → if (true)", mutant("CONDITION_TRUE", 1).description)
        // Negation is dominated by the pair: any test that kills one of them kills it too.
        assertTrue(on(1, "NEGATE_IF").isEmpty())
        assertEquals(1, on(1, "CONDITIONALS_BOUNDARY").size)
    }

    @Test
    fun equalityConditionsLoseTheirNegationToo() {
        assertEquals("same", compiled.call("same", 1, 2, activeId = mutant("CONDITION_TRUE", 2).id))
        assertEquals("diff", compiled.call("same", 2, 2, activeId = mutant("CONDITION_FALSE", 2).id))
        assertTrue(on(2, "NEGATE_EQUALITY").isEmpty())
        assertTrue(on(2, "NEGATE_IF").isEmpty())
    }

    @Test
    fun subjectlessWhenBranches() {
        assertEquals("A", compiled.call("grade", 10, activeId = mutant("CONDITION_TRUE", 3, "n >= 90 → true").id))
        assertEquals("B", compiled.call("grade", 95, activeId = mutant("CONDITION_FALSE", 3, "n >= 90 → false").id))
        assertEquals(2, on(3, "CONDITION_TRUE").size)
        // `when (x)` compares to its subject: NEGATE_EQUALITY covers it, and it is not forced.
        assertTrue(on(12, "CONDITION_TRUE").isEmpty() && on(12, "CONDITION_FALSE").isEmpty())
        // So does `when (this)`, which has no subject variable in IR.
        assertTrue(on(17, "CONDITION_TRUE").isEmpty() && on(17, "CONDITION_FALSE").isEmpty())
        assertEquals(1, on(17, "NEGATE_EQUALITY").size)
        // `is` checks belong to SKIP_IS_BRANCH.
        assertTrue(on(11, "CONDITION_TRUE").isEmpty() && on(11, "CONDITION_FALSE").isEmpty())
        assertEquals(1, on(11, "SKIP_IS_BRANCH").size)
    }

    @Test
    fun clausesOfAndAndOrAreDropped() {
        assertEquals(true, compiled.call("both", false, true, activeId = mutant("CONDITION_TRUE", 4, "a && b → true && b").id))
        assertEquals(true, compiled.call("both", true, false, activeId = mutant("CONDITION_TRUE", 4, "a && b → a && true").id))
        assertEquals(false, compiled.call("either", true, false, activeId = mutant("CONDITION_FALSE", 5, "a || b → false || b").id))
        assertEquals(false, compiled.call("either", false, true, activeId = mutant("CONDITION_FALSE", 5, "a || b → a || false").id))
        assertTrue(on(4, "CONDITION_FALSE").isEmpty() && on(5, "CONDITION_TRUE").isEmpty())
    }

    @Test
    fun smartCastChecksAreOnlyForcedTheSafeWay() {
        // `s != null` forced true would run `s.length` on null; forced false just takes the other branch.
        assertTrue(on(6, "CONDITION_TRUE").isEmpty())
        assertEquals(-1, compiled.call("len", "ab", activeId = mutant("CONDITION_FALSE", 6).id))
        assertEquals(1, on(6, "NEGATE_EQUALITY").size)
        assertTrue(on(7, "CONDITION_FALSE").isEmpty())
        assertEquals(0, compiled.call("blank", "ab", activeId = mutant("CONDITION_TRUE", 7).id))
        // Inside `&&`: the null check is neither dropped nor forced true; `n > 0` may be dropped.
        assertTrue(on(8, "CONDITION_TRUE").none { it.description.startsWith("if") || it.description.contains("true && n") })
        assertEquals(2, compiled.call("guarded", "ab", 0, activeId = mutant("CONDITION_TRUE", 8, "s != null && n > 0 → s != null && true").id))
        assertEquals(0, compiled.call("guarded", "ab", 1, activeId = mutant("CONDITION_FALSE", 8).id))
    }

    @Test
    fun loopConditionsOnlyBecomeFalse() {
        assertEquals(0, compiled.call("count", 3, activeId = mutant("CONDITION_FALSE", 9).id))
        assertEquals("while (i < n) → while (false)", mutant("CONDITION_FALSE", 9).description)
        assertTrue(on(9, "CONDITION_TRUE").isEmpty())
        // A `for` loop's `hasNext()` is compiler plumbing.
        assertTrue(on(10, "CONDITION_FALSE").isEmpty())
    }

    @Test
    fun constantsAndBareCallRemovalsAreLeftAlone() {
        assertTrue(on(13, "CONDITION_TRUE").isEmpty() && on(13, "CONDITION_FALSE").isEmpty())
        // `if (on) log.add("x")`: add returns a value, so no REMOVE_CALL mutant repeats the forced `false`.
        assertEquals(1, on(15, "CONDITION_FALSE").size)
        // `if (on) log.clear()` forced false is the REMOVE_CALL mutant of `log.clear()`.
        assertEquals(1, on(16, "REMOVE_CALL").size)
        assertTrue(on(16, "CONDITION_FALSE").isEmpty())
        assertEquals(1, on(16, "CONDITION_TRUE").size)
    }

    @Test
    fun defaultsAndNegationWithoutThem() {
        val source = "fun label(n: Int): String = if (n > 0) \"pos\" else \"other\""
        val plain = Harness.compile(source)
        assertEquals(listOf("CONDITION_FALSE", "CONDITION_TRUE"), plain.mutants.map { it.operator }.filter { it.startsWith("CONDITION_") }.sorted())
        assertTrue(plain.mutantsOf("NEGATE_IF").isEmpty())
        // With only one of the pair selected, the condition keeps its negation.
        val one = Harness.compile(source, operators = listOf("NEGATE_IF", "CONDITION_TRUE"))
        assertEquals(1, one.mutantsOf("NEGATE_IF").size)
        assertEquals(1, one.mutantsOf("CONDITION_TRUE").size)
    }
}
