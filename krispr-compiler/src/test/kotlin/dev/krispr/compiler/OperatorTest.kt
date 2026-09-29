package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** (a) each operator produces a mutant that changes behaviour when switched on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        // Long returns keep RETURN_VALUE mutants out of the arithmetic cases.
        compiled = Harness.compile(
            """
            fun add(a: Long, b: Long): Long = a + b
            fun sub(a: Long, b: Long): Long = a - b
            fun mul(a: Long, b: Long): Long = a * b
            fun div(a: Long, b: Long): Long = a / b
            fun rem(a: Long, b: Long): Long = a % b
            fun lt(a: Int, b: Int): Boolean = a < b
            fun ge(a: Int, b: Int): Boolean = a >= b
            fun eq(a: Int, b: Int): Boolean = a == b
            fun ne(a: Int, b: Int): Boolean = a != b
            fun and(a: Boolean, b: Boolean): Boolean = a && b
            fun or(a: Boolean, b: Boolean): Boolean = a || b
            fun sign(x: String): String { if (x.isEmpty()) return "empty"; return "full" }
            fun yes(): Boolean = true
            fun answer(): Int = 42
            fun zero(): Int = 0
            """.trimIndent(),
        )
    }

    private fun only(operator: String, line: Int): ManifestEntry =
        compiled.mutants.filter { it.operator == operator && it.line == line }.single()

    private fun assertMutates(function: String, line: Int, operator: String, args: List<Any?>, original: Any?, mutated: Any?) {
        assertEquals(original, compiled.call(function, *args.toTypedArray()), "$function original")
        assertEquals(mutated, compiled.call(function, *args.toTypedArray(), activeId = only(operator, line).id), "$function mutated")
    }

    @Test
    fun arithmetic() {
        assertMutates("add", 1, "MATH", listOf(7L, 3L), 10L, 4L)
        assertMutates("sub", 2, "MATH", listOf(7L, 3L), 4L, 10L)
        assertMutates("mul", 3, "MATH", listOf(8L, 2L), 16L, 4L)
        assertMutates("div", 4, "MATH", listOf(8L, 2L), 4L, 16L)
        assertMutates("rem", 5, "MATH", listOf(7L, 3L), 1L, 21L)
        assertEquals("a + b → a - b", only("MATH", 1).description)
    }

    @Test
    fun relationalBoundary() {
        assertMutates("lt", 6, "CONDITIONALS_BOUNDARY", listOf(2, 2), false, true)
        assertMutates("ge", 7, "CONDITIONALS_BOUNDARY", listOf(2, 2), true, false)
        assertEquals("a < b → a <= b", only("CONDITIONALS_BOUNDARY", 6).description)
    }

    @Test
    fun equalityNegation() {
        assertMutates("eq", 8, "NEGATE_EQUALITY", listOf(1, 1), true, false)
        assertMutates("ne", 9, "NEGATE_EQUALITY", listOf(1, 1), false, true)
        assertEquals("a != b → a == b", only("NEGATE_EQUALITY", 9).description)
    }

    @Test
    fun booleanLogic() {
        assertMutates("and", 10, "BOOLEAN_LOGIC", listOf(true, false), false, true)
        assertMutates("or", 11, "BOOLEAN_LOGIC", listOf(true, false), true, false)
    }

    @Test
    fun negateIf() {
        // By default CONDITION_TRUE and CONDITION_FALSE replace the negation of an `if` condition (ConditionOperatorTest).
        assertEquals(emptyList<ManifestEntry>(), compiled.mutantsOf("NEGATE_IF"))
        val negated = Harness.compile("fun sign(x: String): String { if (x.isEmpty()) return \"empty\"; return \"full\" }", operators = listOf("NEGATE_IF"))
        assertEquals("empty", negated.call("sign", ""))
        assertEquals("full", negated.call("sign", "", activeId = negated.mutantsOf("NEGATE_IF").single().id))
    }

    @Test
    fun returnValues() {
        assertMutates("yes", 13, "RETURN_VALUE", emptyList(), true, false)
        assertMutates("answer", 14, "RETURN_VALUE", emptyList(), 42, 0)
        assertMutates("zero", 15, "RETURN_VALUE", emptyList(), 0, 1)
    }

    @Test
    fun equalityReturnsAreNotDoubleCounted() {
        // `return a == b` negated is the same mutant as `a != b`.
        assertEquals(emptyList<ManifestEntry>(), compiled.mutants.filter { it.line in 8..9 && it.operator == "RETURN_VALUE" })
    }
}
