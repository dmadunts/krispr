package dev.krispr.gradle

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DiffReportWriterTest {
    @Test
    fun rendersTotalsAndSurvivorsGroupedByFile(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val markdown = DiffReportWriter.writeMarkdown(report)

        assertTrue(markdown.startsWith("## krispr diff report\n\n"))
        assertTrue(markdown.contains("10 mutant(s) on changed lines, 4 survived."))
        assertTrue(markdown.contains("### Survivors"))
        assertTrue(markdown.contains("**`src/main/kotlin/Foo.kt`**"))
        assertTrue(markdown.contains("Line 10:"))
        assertTrue(markdown.contains("`a < b` → `a <= b`"))
        assertFalse(markdown.contains("Bar.kt"), "Bar.kt has no survivors and should not be listed")
    }

    @Test
    fun escapesPlainEnglishSoItCannotBreakOutOfTheSurvivorLine() {
        val mutant = MutantReport(
            id = 1, file = "Foo.kt", line = 1, column = 1, operator = "MATH",
            description = "a_b → a*b`c", status = MutantStatus.SURVIVED,
            tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
        )
        val report = Report(
            summary = ReportSummary(
                total = 1, wallMillis = 1, killed = 0, valid = 1, covered = 1,
                mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 1), diffBase = "HEAD",
            ),
            mutants = listOf(mutant),
            maxSurvivorsPerFile = 3,
        )
        val markdown = DiffReportWriter.writeMarkdown(report)

        assertTrue(markdown.contains(codeSpan(mutant.plainEnglish)), markdown)
    }

    @Test
    fun omitsSurvivorsSectionWhenNoneSurvived() {
        val report = Report(
            summary = ReportSummary(
                total = 1, wallMillis = 1, killed = 1, valid = 1, covered = 1,
                mutationScore = 100, coveredScore = 100, counts = mapOf(MutantStatus.KILLED to 1), diffBase = "HEAD",
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val markdown = DiffReportWriter.writeMarkdown(report)
        assertEquals("## krispr diff report\n\n0 mutant(s) on changed lines, 0 survived.\n\n", markdown)
    }

    @Test
    fun capsTotalSurvivorsAcrossAllFilesNotPerFile() {
        // Zero-padded so lexicographic file sort (DiffReportWriter.sortedSurvivors) matches numeric order.
        val mutants = (1..25).map { i ->
            val name = "File%02d".format(i)
            MutantReport(
                id = i, file = "src/main/kotlin/$name.kt", line = i, column = 1, operator = "MATH",
                description = "a + b → a - b", status = MutantStatus.SURVIVED,
                tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
            )
        }
        val report = Report(
            summary = ReportSummary(
                total = 25, wallMillis = 1, killed = 0, valid = 25, covered = 25,
                mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 25), diffBase = "HEAD",
            ),
            mutants = mutants,
            maxSurvivorsPerFile = 100,
        )
        val markdown = DiffReportWriter.writeMarkdown(report)

        assertTrue(markdown.contains("File01.kt"))
        assertTrue(markdown.contains("File20.kt"))
        assertFalse(markdown.contains("File21.kt"), "21st survivor overall should be capped, not listed")
        assertTrue(markdown.contains("_+5 more in the full report_"))
    }

    @Test
    fun annotationsMatchGithubCheckRunShapeAndOnlyCoverSurvivors(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val json = DiffReportWriter.writeAnnotations(report)

        @Suppress("UNCHECKED_CAST")
        val annotations = JsonSlurper().parseText(json) as List<Map<String, Any?>>
        assertEquals(4, annotations.size, "one annotation per survivor, killed/other statuses excluded")
        val first = annotations.first()
        assertEquals(setOf("path", "start_line", "end_line", "annotation_level", "message"), first.keys)
        assertEquals("src/main/kotlin/Foo.kt", first["path"])
        assertEquals(10, first["start_line"])
        assertEquals(10, first["end_line"])
        assertEquals("warning", first["annotation_level"])
        assertTrue((first["message"] as String).isNotBlank())
    }

    @Test
    fun writesEmptyAnnotationsArrayWhenNoSurvivors() {
        val report = Report(
            summary = ReportSummary(
                total = 0, wallMillis = 0, killed = 0, valid = 0, covered = 0,
                mutationScore = null, coveredScore = null, counts = emptyMap(), diffBase = "HEAD",
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val json = DiffReportWriter.writeAnnotations(report)
        assertEquals(emptyList<Any>(), JsonSlurper().parseText(json))
    }
}
