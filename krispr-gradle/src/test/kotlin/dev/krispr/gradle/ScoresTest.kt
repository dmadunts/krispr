package dev.krispr.gradle

import dev.krispr.gradle.MutantStatus.KILLED
import dev.krispr.gradle.MutantStatus.MEMORY_ERROR
import dev.krispr.gradle.MutantStatus.NOT_MEASURED
import dev.krispr.gradle.MutantStatus.NO_COVERAGE
import dev.krispr.gradle.MutantStatus.RUN_ERROR
import dev.krispr.gradle.MutantStatus.SURVIVED
import dev.krispr.gradle.MutantStatus.TIMED_OUT
import dev.krispr.gradle.MutantStatus.UNKNOWN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScoresTest {
    @Test
    fun notMeasuredIsOutOfBothDenominatorsAndUnknownIsNeverKilled() {
        val scores = KrisprRunTask.Scores(
            listOf(KILLED, KILLED, TIMED_OUT, SURVIVED, UNKNOWN, RUN_ERROR, NO_COVERAGE, NO_COVERAGE, NOT_MEASURED, NOT_MEASURED),
        )
        assertEquals(3, scores.killed)
        assertEquals(8, scores.valid)
        assertEquals(6, scores.covered)
        assertEquals(37, scores.ofValid)
        assertEquals(50, scores.ofCovered)
    }

    @Test
    fun memoryErrorsCountAsKilled() {
        assertEquals(2, KrisprRunTask.Scores(listOf(MEMORY_ERROR, KILLED, SURVIVED)).killed)
    }

    @Test
    fun anEmptyDenominatorHasNoScore() {
        val scores = KrisprRunTask.Scores(listOf(NO_COVERAGE, NOT_MEASURED))
        assertEquals(0, scores.ofValid)
        assertNull(scores.ofCovered)
        assertNull(KrisprRunTask.Scores(emptyList()).ofValid)
    }
}
