package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReportReaderTest {
    @Test
    fun readsSummaryMutantsAndMaxSurvivorsPerFile(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))

        assertEquals(10, report.summary.total)
        assertEquals(2, report.summary.killed)
        assertEquals(9, report.summary.valid)
        assertEquals(8, report.summary.covered)
        assertEquals(22, report.summary.mutationScore)
        assertEquals(25, report.summary.coveredScore)
        assertEquals(1, report.summary.counts[MutantStatus.NOT_MEASURED])
        assertEquals(1, report.summary.counts[MutantStatus.UNKNOWN])
        assertEquals(10, report.mutants.size)
        assertEquals(4, report.survivors.size)
        assertEquals(2, report.maxSurvivorsPerFile)
    }

    @Test
    fun splitsDescriptionIntoOriginalAndMutated() {
        val mutant = MutantReport(
            id = 1, file = "Foo.kt", line = 1, column = 1, operator = "MATH", description = "a + b → a - b",
            status = MutantStatus.SURVIVED, tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
        )
        assertEquals("a + b", mutant.original)
        assertEquals("a - b", mutant.mutated)
    }

    @Test
    fun defaultsMaxSurvivorsPerFileToThreeWhenAbsent(@TempDir dir: File) {
        val withoutField = fixtureReport(dir).readText().replace("\"maxSurvivorsPerFile\": 2,", "")
        val file = File(dir, "no-cap.json").apply { writeText(withoutField) }
        assertEquals(3, ReportReader.read(file).maxSurvivorsPerFile)
    }

    @Test
    fun diffBaseIsNullWhenAbsentAndReadWhenPresent(@TempDir dir: File) {
        assertEquals(null, ReportReader.read(fixtureReport(dir)).summary.diffBase)

        val withDiffBase = fixtureReport(dir).readText()
            .replace("\"total\": 10,", "\"total\": 10, \"diffBase\": \"origin/main\",")
        val file = File(dir, "with-diff-base.json").apply { writeText(withDiffBase) }
        assertEquals("origin/main", ReportReader.read(file).summary.diffBase)
    }
}
