package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Mutants that run once per class load are flagged, so the run credits them to every test. */
class InitializerTest {
    @Test
    fun flagsTopLevelObjectAndCompanionInitializersOnly() {
        val mutants = Harness.compile(
            """
            val pageSize: Long = 25L - 5L
            val lazyLimit: Long by lazy { 4L * 2L }
            object Limits {
                val max: Long = 10L * 2L
                init { check(max > 1L) }
            }
            class Config(start: Long) {
                val first: Long = start + 1L
                init { check(start < 100L) }
                companion object { val retries: Long = 3L + 1L }
                fun next(x: Long): Long = x - 1L
            }
            """.trimIndent(),
        ).mutants
        val flagged = mutants.filter { it.initializer }.map { it.description }.toSet()
        val others = mutants.filterNot { it.initializer }.map { it.description }.toSet()
        assertEquals(setOf("25L - 5L → 25L + 5L", "4L * 2L → 4L / 2L", "10L * 2L → 10L / 2L", "max > 1L → max >= 1L", "check(max > 1L) → (removed)", "3L + 1L → 3L - 1L"), flagged)
        assertEquals(setOf("start + 1L → start - 1L", "check(start < 100L) → (removed)", "start < 100L → start <= 100L", "x - 1L → x + 1L"), others)
    }
}
