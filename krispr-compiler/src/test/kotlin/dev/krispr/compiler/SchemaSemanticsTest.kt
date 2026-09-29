package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** (b) with no active mutant the instrumented code behaves exactly like the original. */
class SchemaSemanticsTest {
    private val source = """
        class Account(private var balance: Long) {
            fun deposit(amount: Long): Boolean {
                if (amount <= 0L) return false
                balance = balance + amount
                return true
            }
            fun fee(): Long = if (balance > 1000L && balance % 2L == 0L) balance / 100L else 5L
            fun current(): Long = balance
        }

        fun scenario(): String {
            val account = Account(900L)
            val first = account.deposit(0L)
            val second = account.deposit(200L)
            val fee = account.fee()
            return "${'$'}first ${'$'}second ${'$'}fee ${'$'}{account.current()}"
        }

        fun grade(score: Int): Char = when {
            score >= 90 -> 'A'
            score >= 75 || score == 42 -> 'B'
            score > 50 -> 'C'
            else -> 'F'
        }

        fun operandsEvaluatedOnce(): Long {
            var calls = 0L
            fun next(): Long { calls = calls + 1L; return calls }
            val sum = next() + next()
            return calls * 10L + sum
        }

        fun shortCircuit(): Int {
            var bumps = 0
            fun bump(): Boolean { bumps = bumps + 1; return true }
            val ignored = false && bump()
            return bumps
        }
    """.trimIndent()

    @Test
    fun unchangedWithoutActiveMutant() {
        val compiled = Harness.compile(source)
        assertTrue(compiled.mutants.size > 10, "expected the sample to be instrumented, got ${compiled.mutants.size}")
        assertEquals("false true 11 1100", compiled.call("scenario"))
        assertEquals(listOf('A', 'B', 'B', 'C', 'F'), listOf(95, 80, 42, 60, 10).map { compiled.call("grade", it) })
        assertEquals(23L, compiled.call("operandsEvaluatedOnce"))
        assertEquals(0, compiled.call("shortCircuit"))
    }

    @Test
    fun mutatedSitesEvaluateOperandsOnceAndKeepShortCircuiting() {
        val compiled = Harness.compile(source)
        val sum = compiled.mutants.single { it.description == "next() + next() → next() - next()" }
        // Still two calls: 1 - 2 = -1, so 20 - 1.
        assertEquals(19L, compiled.call("operandsEvaluatedOnce", activeId = sum.id))

        val logic = compiled.mutants.single { it.description == "false && bump() → false || bump()" }
        assertEquals(1, compiled.call("shortCircuit", activeId = logic.id))
    }
}
