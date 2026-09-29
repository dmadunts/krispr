package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TerminalReportWriterTest {
    @Test
    fun headlineReportsCountsAndBothScores(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))

        assertTrue(text.contains("krispr: 10 mutants: 2 killed (1 timed out), 4 survived, 1 unknown, 1 no coverage, 1 run errors;"), text)
        assertTrue(text.contains("score 25% of covered, 22% of valid, 1 not measured"), text)
    }

    @Test
    fun headlineCountsTimeoutsOnASlowHostApartFromKills(@TempDir dir: File) {
        fun mutant(id: Int, status: MutantStatus, reason: String? = null) = MutantReport(
            id = id, file = "Foo.kt", line = id, column = 1, operator = "MATH", description = "a → b",
            status = status, tests = emptyList(), killedBy = null, millis = 0, runner = "fork", reason = reason,
        )
        val report = Report(
            summary = ReportSummary(
                total = 3, wallMillis = 1, killed = 1, valid = 3, covered = 3, mutationScore = 33, coveredScore = 33,
                counts = mapOf(MutantStatus.TIMED_OUT to 1, MutantStatus.UNKNOWN to 2),
            ),
            mutants = listOf(
                mutant(1, MutantStatus.TIMED_OUT),
                mutant(2, MutantStatus.UNKNOWN, "${Timeouts.HOST_TOO_SLOW}: its tests took 9000 ms of the 10000 ms timeout without the mutant"),
                mutant(3, MutantStatus.UNKNOWN, "its tests did not activate the mutant, twice"),
            ),
            maxSurvivorsPerFile = 0,
        )
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))
        assertTrue(text.contains("3 mutants: 1 killed (1 timed out), 0 survived, 2 unknown (1 timed out on a slow host), "), text)
    }

    @Test
    fun printsNoColorCodesWhenColorIsOff(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))
        assertFalse(text.contains("\u001B["), text)
    }

    @Test
    fun wrapsInAnsiCodesWhenColorIsOn(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val text = TerminalReportWriter.write(report, dir, color = true, reportFile = File(dir, "report.json"))
        assertTrue(text.contains("\u001B["), text)
    }

    @Test
    fun survivorsAreOrderedByOperatorPriorityBeforeLineNumber() {
        // By line: 10 CONDITIONALS_BOUNDARY, 20 MATH, 25 NEGATE_IF, 45 INCREMENTS. NEGATE_IF and
        // CONDITIONALS_BOUNDARY are tier 0 (branch/condition), MATH and INCREMENTS are tier 2 (arithmetic),
        // so despite line order the tier-0 pair must print first, ordered by line within the tier.
        fun survivor(id: Int, line: Int, operator: String) = MutantReport(
            id = id, file = "Foo.kt", line = line, column = 1, operator = operator, description = "a → b",
            status = MutantStatus.SURVIVED, tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
        )
        val report = Report(
            summary = ReportSummary(total = 4, wallMillis = 1, killed = 0, valid = 4, covered = 4, mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 4)),
            mutants = listOf(
                survivor(1, 10, "CONDITIONALS_BOUNDARY"), survivor(2, 20, "MATH"),
                survivor(3, 25, "NEGATE_IF"), survivor(4, 45, "INCREMENTS"),
            ),
            maxSurvivorsPerFile = 0,
        )
        val text = TerminalReportWriter.write(report, File("."), color = false, reportFile = File("report.json"))
        val order = listOf("Foo.kt:10", "Foo.kt:25", "Foo.kt:20", "Foo.kt:45").map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 }, text)
        assertEquals(order.sorted(), order, "expected CONDITIONALS_BOUNDARY(10), NEGATE_IF(25), MATH(20), INCREMENTS(45): $text")
    }

    @Test
    fun capsSurvivorsPerFileAndSaysHowManyMore(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))
        // maxSurvivorsPerFile is 2 in the fixture; Foo.kt has 4 survivors.
        assertTrue(text.contains("src/main/kotlin/Foo.kt: +2 more in the report"), text)
    }

    @Test
    fun summarisesNoCoveragePerFileRatherThanListingEachMutant(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))
        assertTrue(text.contains("src/main/kotlin/Bar.kt: 1 mutant with no covering test"), text)
    }

    @Test
    fun rendersEmptyReportWithoutSurvivedOrNoCoverageSections() {
        val report = Report(
            summary = ReportSummary(total = 0, wallMillis = 0, killed = 0, valid = 0, covered = 0, mutationScore = null, coveredScore = null, counts = emptyMap()),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val text = TerminalReportWriter.write(report, File("."), color = false, reportFile = File("report.json"))
        assertFalse(text.contains("Survived:"), text)
        assertFalse(text.contains("No coverage:"), text)
        assertTrue(text.contains("krispr: 0 mutants:"), text)
    }

    @Test
    fun showsSourceLineAndPlainEnglishDescriptionInline() {
        val dir = File.createTempFile("krispr", "term").apply { delete(); mkdirs() }
        File(dir, "Foo.kt").writeText("val a = 1\nif (a > b) foo()\n")
        val report = Report(
            summary = ReportSummary(total = 1, wallMillis = 1, killed = 0, valid = 1, covered = 1, mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 1)),
            mutants = listOf(
                MutantReport(
                    id = 1, file = "Foo.kt", line = 2, column = 4, operator = "CONDITION_TRUE",
                    description = "a > b → true", status = MutantStatus.SURVIVED, tests = listOf("FooTest.test"),
                    killedBy = null, millis = 1, runner = null, reason = null,
                ),
            ),
            maxSurvivorsPerFile = 3,
        )
        val text = TerminalReportWriter.write(report, dir, color = false, reportFile = File(dir, "report.json"))
        assertTrue(text.contains("if (a > b) foo()"), text)
        assertTrue(text.contains("a > b → true"), text)
        assertTrue(text.contains("`a > b` forced true; no test failed."), text)
        dir.deleteRecursively()
    }
}
