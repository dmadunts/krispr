package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestValueAnalysisTest {
    private fun killedMutant(id: Int, killers: List<String>) = MutantReport(
        id = id, file = "F.kt", line = 1, column = 1, operator = "MATH", description = "a → b",
        status = MutantStatus.KILLED, tests = killers, killedBy = killers.first(), millis = 1, runner = "fork",
        reason = null, killers = killers, testedTests = killers,
    )

    private fun report(mutants: List<MutantReport>, killMatrix: Boolean = true) = Report(
        summary = ReportSummary(
            total = mutants.size, wallMillis = 1, killed = mutants.size, valid = mutants.size, covered = mutants.size,
            mutationScore = 100, coveredScore = 100, counts = mapOf(MutantStatus.KILLED to mutants.size), killMatrix = killMatrix,
        ),
        mutants = mutants,
        maxSurvivorsPerFile = 3,
    )

    @Test
    fun twoTestsWithTheSameKillsAreSubsumedInOneDirectionOnly() {
        // "A" and "B" both kill both mutants: neither is more thorough than the other.
        val result = TestValueAnalysis.analyze(report(listOf(killedMutant(1, listOf("A", "B")), killedMutant(2, listOf("A", "B")))))

        assertEquals(listOf("B" to "A"), result.subsumedByOneTest)
    }

    @Test
    fun testsWithFewerKillsAreSubsumedByTheOneThatCoversBothMutants() {
        // "A" kills both mutants; "B" kills only mutant 2 and "C" kills only mutant 1, so both are subsets of A.
        val result = TestValueAnalysis.analyze(
            report(listOf(killedMutant(1, listOf("A", "C")), killedMutant(2, listOf("A", "B")))),
        )

        assertEquals(listOf("B" to "A", "C" to "A"), result.subsumedByOneTest)
    }
}
