package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Property initializers, `init` blocks, default values, compound assignment, `++`/`--`, unary minus. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewSitesTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        // Long everywhere keeps RETURN_VALUE (Int and Boolean only) out of the way.
        compiled = Harness.compile(
            """
            val derived: Long = seed() * 3L
            fun seed(): Long = 7L
            fun derivedValue(): Long = derived

            class Counter(start: Long) {
                var value: Long = start + 1L
                init { value -= 2L }
            }
            fun counterValue(start: Long): Long = Counter(start).value

            fun scaled(x: Long, factor: Long = x * 2L): Long = factor; fun scaledOf(x: Long): Long = scaled(x)
            class Box(val size: Long = seed() + 1L)
            fun boxSize(): Long = Box().size

            fun compound(x: Long): Long { var r = x; r += 3L; r *= 2L; return r }
            fun incr(x: Long): Long { var i = x; i++; ++i; return i }
            fun decr(x: Long): Long { var i = x; i--; return i }
            class Tally { var n: Long = 0L; fun bump(): Long { n++; return n } }
            fun tally(): Long = Tally().bump()
            fun negate(x: Long): Long = -x
            fun negativeLiteral(): Long = -5L
            const val LIMIT: Long = 2L * 3L
            fun limit(): Long = LIMIT
            fun intLoop(n: Int): Long { var total = 0L; var i = 0; while (i < n) { total += i; ++i }; for (j in 0 until n) { i-- }; return total + i }
            fun statementIncrement(c: Char): Long { var depth = 0; when (c) { 'a' -> depth++; 'b' -> depth-- }; return depth.toLong() }
            fun skip(s: String): Long { var p = 0; var hops = 0L; while (p < s.length) { if (s[p] == 'x') p += 2 else p++; hops++ }; return hops }
            """.trimIndent(),
        )
    }

    private fun on(operator: String, line: Int): List<ManifestEntry> =
        compiled.mutants.filter { it.operator == operator && it.line == line }

    private fun check(function: String, args: List<Any?>, original: Any?, vararg mutants: Pair<ManifestEntry, Any?>, fresh: Boolean = false) {
        assertEquals(original, compiled.call(function, *args.toTypedArray(), fresh = fresh), "$function original")
        for ((mutant, expected) in mutants) {
            assertEquals(expected, compiled.call(function, *args.toTypedArray(), activeId = mutant.id, fresh = fresh), "$function ${mutant.description}")
        }
    }

    @Test
    fun topLevelPropertyInitializer() {
        val mutant = on("MATH", 1).single()
        assertEquals("seed() * 3L → seed() / 3L", mutant.description)
        assertEquals("derived", mutant.declaration)
        check("derivedValue", emptyList(), 21L, mutant to 2L, fresh = true)
    }

    @Test
    fun memberPropertyInitializerAndInitBlock() {
        val initializer = on("MATH", 6).single()
        val initBlock = on("MATH", 7).single()
        assertEquals("value -= 2L → value += 2L", initBlock.description)
        assertEquals("Counter.<init-block>", initBlock.declaration)
        check("counterValue", listOf(10L), 9L, initializer to 7L, initBlock to 13L)
    }

    @Test
    fun defaultParameterValues() {
        check("scaledOf", listOf(5L), 10L, on("MATH", 11).single() to 2L)
        check("boxSize", emptyList(), 8L, on("MATH", 12).single() to 6L)
    }

    @Test
    fun compoundAssignment() {
        val (plus, times) = on("MATH", 15)
        assertEquals(listOf("r += 3L → r -= 3L", "r *= 2L → r /= 2L"), listOf(plus.description, times.description))
        check("compound", listOf(4L), 14L, plus to 2L, times to 3L)
        // Int locals: the JVM backend would turn `p += 2` into `iinc` by matching the set's origin.
        val intPlus = compiled.mutants.single { it.line == 26 && it.description == "p += 2 → p -= 2" }
        assertEquals(2L, compiled.call("skip", "xab"))
        assertEquals(3L, compiled.call("skip", "abc", activeId = intPlus.id))
    }

    @Test
    fun increments() {
        val (postfix, prefix) = on("INCREMENTS", 16)
        assertEquals(listOf("i++ → i--", "++i → --i"), listOf(postfix.description, prefix.description))
        check("incr", listOf(5L), 7L, postfix to 5L, prefix to 5L)
        check("decr", listOf(5L), 4L, on("INCREMENTS", 17).single() to 6L)
        check("tally", emptyList(), 1L, on("INCREMENTS", 18).single() to -1L)
    }

    @Test
    fun intLocalIncrementsStillCompileAndRun() {
        // Int locals are where the JVM backend turns `++i` into `iinc`, by pattern-matching the block.
        val (prefix, postfix) = on("INCREMENTS", 24)
        assertEquals(listOf("++i → --i", "i-- → i++"), listOf(prefix.description, postfix.description))
        assertEquals(6L, compiled.call("intLoop", 4))
        assertEquals(14L, compiled.call("intLoop", 4, activeId = postfix.id))
        val (up, down) = on("INCREMENTS", 25)
        assertEquals(listOf("depth++ → depth--", "depth-- → depth++"), listOf(up.description, down.description))
        assertEquals(1L, compiled.call("statementIncrement", 'a'))
        assertEquals(-1L, compiled.call("statementIncrement", 'a', activeId = up.id))
        assertEquals(1L, compiled.call("statementIncrement", 'b', activeId = down.id))
    }

    @Test
    fun unaryMinus() {
        val mutant = on("INVERT_NEGS", 20).single()
        assertEquals("-x → x", mutant.description)
        check("negate", listOf(3L), -3L, mutant to 3L)
    }

    @Test
    fun literalsAndConstantsAreNotMutated() {
        assertEquals(emptyList<ManifestEntry>(), compiled.mutants.filter { it.line in 21..23 })
        assertEquals(6L, compiled.call("limit"))
    }
}
