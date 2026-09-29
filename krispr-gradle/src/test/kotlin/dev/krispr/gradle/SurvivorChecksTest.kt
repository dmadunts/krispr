package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurvivorChecksTest {
    @Test
    fun onlyASurvivorThatRanARobolectricTestNeedsAFreshRun() {
        val checks = SurvivorChecks(enabled = true)
        assertTrue(checks.needed(MutantStatus.SURVIVED, frameworkMillis = 12))
        // No timing line: whether a sandbox was involved is unknown, so check it.
        assertTrue(checks.needed(MutantStatus.SURVIVED, frameworkMillis = null))
        // Plain tests only: they load the project's classes afresh for each mutant.
        assertFalse(checks.needed(MutantStatus.SURVIVED, frameworkMillis = 0))
        for (status in MutantStatus.entries - MutantStatus.SURVIVED) assertFalse(checks.needed(status, 12), "$status")
    }

    @Test
    fun offNeedsNothing() {
        assertFalse(SurvivorChecks(enabled = false).needed(MutantStatus.SURVIVED, 12))
    }

    @Test
    fun countsFreshVerdictsThatDisagree() {
        val checks = SurvivorChecks(enabled = true)
        assertFalse(checks.record(MutantStatus.SURVIVED))
        assertTrue(checks.record(MutantStatus.KILLED))
        assertTrue(checks.record(MutantStatus.UNKNOWN))
        assertEquals("krispr: 3 survivors re-checked in fresh JVMs, 2 changed", checks.summary())
    }
}
