package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Swaps with a literal operand that cannot change the result get no mutant; the same swaps elsewhere do. */
class EquivalentMutantTest {

    @Test
    fun noSwapWhereTheLiteralMakesItEquivalent() {
        val mutants = Harness.compile(
            """
            fun plusZero(x: Long): Long = x + 0L
            fun minusZero(x: Long): Long = x - 0L
            fun timesOne(x: Long): Long = x * 1L
            fun divMinusOne(x: Long): Long = x / -1L
            fun timesOneDouble(x: Double): Double = x * 1.0
            fun compound(x: Long): Long { var r = x; r += 0L; r *= 1L; return r }
            """.trimIndent(),
        ).mutants
        assertEquals(emptyList<String>(), mutants.map { it.description })
    }

    @Test
    fun literalsThatDoNotMakeTheSwapEquivalentKeepTheirMutants() {
        val mutants = Harness.compile(
            """
            fun zeroMinus(x: Long): Long = 0L - x
            fun oneDiv(x: Long): Long = 1L / x
            fun timesTwo(x: Long): Long = x * 2L
            fun plusOne(x: Long): Long = x + 1L
            fun plusZeroDouble(x: Double): Double = x + 0.0
            fun remOne(x: Long): Long = x % 1L
            """.trimIndent(),
        ).mutants
        assertEquals(
            listOf("0L - x → 0L + x", "1L / x → 1L * x", "x * 2L → x / 2L", "x + 1L → x - 1L", "x + 0.0 → x - 0.0", "x % 1L → x * 1L"),
            mutants.map { it.description },
        )
    }
}
