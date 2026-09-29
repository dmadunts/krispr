package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HtmlReportWriterTest {
    @Test
    fun rendersBothScoresBesideNotMeasuredAndUnknownCounts(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val html = HtmlReportWriter.write(report, dir)

        assertTrue(html.contains("<span class=\"value\">25%</span><span class=\"label\">of covered</span>"))
        assertTrue(html.contains("<span class=\"value\">22%</span><span class=\"label\">of valid</span>"))
        // NOT_MEASURED and UNKNOWN must appear as their own stat entries, never merged into the score value above.
        assertTrue(html.contains("stat-NOT_MEASURED\">1 not measured"))
        assertTrue(html.contains("stat-UNKNOWN\">1 unknown"))
    }

    @Test
    fun rendersOneFilterCheckboxPerStatusAndOnePerOperatorWhenThereAreMutants(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val html = HtmlReportWriter.write(report, dir)
        for (status in MutantStatus.entries) {
            assertTrue(html.contains("data-status=\"${status.name}\" checked"), "missing filter checkbox for $status")
        }
        for (operator in report.mutants.map { it.operator }.distinct()) {
            assertTrue(html.contains("data-operator=\"${operator}\" checked"), "missing filter checkbox for $operator")
        }
    }

    @Test
    fun rendersNoFiltersOrOverviewAndSaysNoMutantsWhenTheReportIsEmpty() {
        val html = HtmlReportWriter.write(emptyReport(), File("."))
        assertFalse(html.contains("input type=\"checkbox\""))
        assertFalse(html.contains("class=\"overview\""))
        assertTrue(html.contains("No mutants."))
    }

    @Test
    fun fallsBackToAListWhenSourceFileIsMissing(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val html = HtmlReportWriter.write(report, dir)

        assertTrue(html.contains("Source not found under the project directory; showing mutants only."))
        assertFalse(html.contains("<table class=\"source\">"))
    }

    @Test
    fun rendersSourceInlineWithAClickableGutterMarkerAndAPlainEnglishDescription() {
        val dir = File.createTempFile("krispr", "html").apply { delete(); mkdirs() }
        File(dir, "src/main/kotlin").mkdirs()
        File(dir, "src/main/kotlin/Foo.kt").writeText("val a = 1\nif (a < 2) foo()\n")

        val report = singleSurvivorReport("src/main/kotlin/Foo.kt", 2, 4, "CONDITIONALS_BOUNDARY", "a < 2 → a <= 2")
        val html = HtmlReportWriter.write(report, dir)

        assertTrue(html.contains("<table class=\"source\">"))
        assertTrue(html.contains("if (a &lt; 2) foo()"))
        assertTrue(html.contains("tr class=\"line has-mutants SURVIVED\""))
        assertTrue(html.contains("class=\"marker status-SURVIVED\""))
        assertTrue(html.contains("aria-expanded=\"true\""), html) // SURVIVED starts expanded
        assertTrue(html.contains("tr class=\"mutant status-SURVIVED\" data-status=\"SURVIVED\" data-operator=\"CONDITIONALS_BOUNDARY\""))
        assertTrue(html.contains("<code>a &lt; 2</code> → <code>a &lt;= 2</code>"))
        assertTrue(html.contains("class=\"plain\">`a &lt; 2` became `a &lt;= 2`; no test failed."), html)
        dir.deleteRecursively()
    }

    @Test
    fun nonSurvivedMutantsStartCollapsed() {
        val dir = File.createTempFile("krispr", "html").apply { delete(); mkdirs() }
        File(dir, "Foo.kt").writeText("val a = 1\n")
        val report = singleSurvivorReport("Foo.kt", 1, 1, "MATH", "1 → 0", status = MutantStatus.KILLED)
        val html = HtmlReportWriter.write(report, dir)

        assertTrue(html.contains("tr class=\"mutant status-KILLED collapsed\""), html)
        assertTrue(html.contains("aria-expanded=\"false\""), html)
        assertTrue(!html.contains("no test failed"), "a killed mutant must not read as surviving")
        dir.deleteRecursively()
    }

    @Test
    fun overviewTableLinksToEachFileSectionOrderedWorstScoreFirst() {
        val worse = MutantReport(
            id = 1, file = "Bad.kt", line = 1, column = 1, operator = "MATH", description = "a → b",
            status = MutantStatus.SURVIVED, tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
        )
        val better = MutantReport(
            id = 2, file = "Good.kt", line = 1, column = 1, operator = "MATH", description = "a → b",
            status = MutantStatus.KILLED, tests = emptyList(), killedBy = "T", millis = 0, runner = null, reason = null,
        )
        val report = Report(
            summary = ReportSummary(total = 2, wallMillis = 1, killed = 1, valid = 2, covered = 2, mutationScore = 50, coveredScore = 50, counts = mapOf(MutantStatus.SURVIVED to 1, MutantStatus.KILLED to 1)),
            mutants = listOf(worse, better),
            maxSurvivorsPerFile = 3,
        )
        val html = HtmlReportWriter.write(report, File("."))
        assertTrue(html.contains("<a href=\"#file-0\">Bad.kt</a>"))
        assertTrue(html.contains("<a href=\"#file-1\">Good.kt</a>"))
        assertTrue(html.indexOf("Bad.kt</a>") < html.indexOf("Good.kt</a>"), "worst score (Bad.kt, 0%) should be listed before Good.kt (100%)")
        assertTrue(html.contains("id=\"file-0\""))
        assertTrue(html.contains("id=\"file-1\""))
    }

    @Test
    fun supportsLightAndDarkViaPrefersColorScheme() {
        val html = HtmlReportWriter.write(emptyReport(), File("."))
        assertTrue(html.contains("@media (prefers-color-scheme: dark)"))
    }

    @Test
    fun escapesHostileFileSourceDescriptionAndTestNamesInHtml() {
        val html = HtmlReportWriter.write(nastyReport(), File("."))
        // The report's own filter script legitimately uses raw "<script>" and "&&"; only the generated
        // report body (<main>...</main>) is built from mutant-controlled content that must be escaped.
        val body = html.substringAfter("<main>").substringBefore("</main>")

        assertFalse(body.contains("<script>alert(1)</script>"), body)
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), body)
        // Every '&' in the body must belong to a recognised entity: proof no raw markup slipped through unescaped.
        assertFalse(Regex("&(?!amp;|lt;|gt;|quot;|#)").containsMatchIn(body), body)
    }

    @Test
    fun largeNotMeasuredAndUnknownCountsNeverDiluteTheDisplayedScore() {
        // Mirrors ScoresTest and docs/PHILOSOPHY.md "Honest statuses": NOT_MEASURED is out of both
        // denominators, and UNKNOWN never enters the numerator. This proves the HTML writer trusts the
        // precomputed summary score fields rather than recomputing from `counts`, which would let a huge
        // NOT_MEASURED/UNKNOWN count dilute a genuine 100% score.
        val report = Report(
            summary = ReportSummary(
                total = 2001, wallMillis = 1, killed = 1, valid = 1, covered = 1,
                mutationScore = 100, coveredScore = 100,
                counts = mapOf(MutantStatus.KILLED to 1, MutantStatus.NOT_MEASURED to 1000, MutantStatus.UNKNOWN to 1000),
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val html = HtmlReportWriter.write(report, File("."))

        assertTrue(html.contains("<span class=\"value\">100%</span><span class=\"label\">of covered</span>"))
        assertTrue(html.contains("<span class=\"value\">100%</span><span class=\"label\">of valid</span>"))
        assertTrue(html.contains("stat-NOT_MEASURED\">1000 not measured"))
        assertTrue(html.contains("stat-UNKNOWN\">1000 unknown"))
    }

    @Test
    fun rendersFiveThousandMutantsWithoutError() {
        val mutants = (1..5000).map { i ->
            MutantReport(
                id = i, file = "File${i % 50}.kt", line = (i % 200) + 1, column = 1, operator = "MATH",
                description = "a → b", status = MutantStatus.entries[i % MutantStatus.entries.size],
                tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
            )
        }
        val report = Report(
            summary = ReportSummary(total = 5000, wallMillis = 1000, killed = 1000, valid = 4000, covered = 3000, mutationScore = 25, coveredScore = 33, counts = emptyMap()),
            mutants = mutants,
            maxSurvivorsPerFile = 3,
        )
        val html = HtmlReportWriter.write(report, File("."))
        assertTrue(html.contains("<html"))
        assertTrue(html.trim().endsWith("</html>"))
    }

    private fun singleSurvivorReport(
        file: String,
        line: Int,
        column: Int,
        operator: String,
        description: String,
        status: MutantStatus = MutantStatus.SURVIVED,
    ) = Report(
        summary = ReportSummary(
            total = 1, wallMillis = 1, killed = 0, valid = 1, covered = 1,
            mutationScore = 0, coveredScore = 0, counts = mapOf(status to 1),
        ),
        mutants = listOf(
            MutantReport(
                id = 1, file = file, line = line, column = column, operator = operator,
                description = description, status = status, tests = listOf("FooTest.test"),
                killedBy = null, millis = 5, runner = null, reason = null,
            ),
        ),
        maxSurvivorsPerFile = 3,
    )

    private fun emptyReport() = Report(
        summary = ReportSummary(
            total = 0, wallMillis = 0, killed = 0, valid = 0, covered = 0,
            mutationScore = null, coveredScore = null, counts = emptyMap(),
        ),
        mutants = emptyList(),
        maxSurvivorsPerFile = 3,
    )
}
