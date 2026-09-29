package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KrisprRunTaskTest {
    @Test
    fun batchEndMatchesTestRunsGrowingBatches() {
        // Batches of 10 selectors: [0], [1], [2,3], [4,5,6,7], [8,9].
        assertEquals(1, batchEnd(0, 10))
        assertEquals(2, batchEnd(1, 10))
        assertEquals(4, batchEnd(2, 10))
        assertEquals(4, batchEnd(3, 10))
        assertEquals(8, batchEnd(4, 10))
        assertEquals(8, batchEnd(7, 10))
        assertEquals(10, batchEnd(8, 10))
        assertEquals(10, batchEnd(9, 10))
    }

    @Test
    fun batchEndClampsToTheTotalForASingleOrEmptyRun() {
        assertEquals(1, batchEnd(0, 1))
        assertEquals(0, batchEnd(0, 0))
    }
}
