package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** `is` branches of a `when` (SKIP_IS_BRANCH): each type check can be made to fail. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BranchOperatorTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            sealed interface Shape; class Circle(val r: Int) : Shape; class Square(val side: Int) : Shape; class Dot : Shape
            fun area(s: Shape): Int = when (s) { is Circle -> 3 * s.r * s.r; is Square -> s.side * s.side; is Dot -> 0 }
            fun kind(x: Any): String = when (x) { !is String -> "other"; else -> "text" }
            fun label(x: Any): String = when { x is Int -> "int"; else -> "any" }
            fun sign(x: Any): String = if (x is Int) "int" else "any"
            fun digit(x: Int): String = when (x) { 1 -> "one"; else -> "many" }
            """.trimIndent(),
        )
    }

    private fun skip(description: String): ManifestEntry =
        compiled.mutantsOf("SKIP_IS_BRANCH").singleOrNull { it.description == description } ?: error("no '$description' in ${compiled.mutants}")

    @Test
    fun isBranchesOfASubjectWhenAreSkipped() {
        assertEquals(12, compiled.call("area", compiled.classLoader.loadClass("Circle").getConstructor(Int::class.java).newInstance(2)))
        val circle = compiled.classLoader.loadClass("Circle").getConstructor(Int::class.java).newInstance(2)
        // Falls through to `is Square`, then `is Dot`, then the exhaustive when's implicit else.
        val thrown = runCatching { compiled.call("area", circle, activeId = skip("is Circle → false").id) }.exceptionOrNull()
        assertEquals("NoWhenBranchMatchedException", thrown?.javaClass?.simpleName)
        val square = compiled.classLoader.loadClass("Square").getConstructor(Int::class.java).newInstance(3)
        assertEquals(9, compiled.call("area", square, activeId = skip("is Circle → false").id))
        assertEquals(3, compiled.mutantsOf("SKIP_IS_BRANCH").count { it.line == 2 })
    }

    @Test
    fun negatedAndSubjectlessTypeChecksToo() {
        assertEquals("other", compiled.call("kind", 1))
        assertEquals("text", compiled.call("kind", 1, activeId = skip("!is String → false").id))
        assertEquals("any", compiled.call("label", 1, activeId = skip("x is Int → false").id))
    }

    @Test
    fun onlyWhenTypeChecks() {
        // `if (x is Int)` keeps its NEGATE_IF mutant; a subject `when`'s equality keeps NEGATE_EQUALITY.
        assertEquals(listOf(2, 2, 2, 3, 4), compiled.mutantsOf("SKIP_IS_BRANCH").map { it.line }.sorted())
        assertEquals(1, compiled.mutants.count { it.line == 5 && it.operator == "NEGATE_IF" })
        assertEquals(1, compiled.mutants.count { it.line == 6 && it.operator == "NEGATE_EQUALITY" })
    }
}
