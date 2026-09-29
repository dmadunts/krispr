package dev.krispr.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ShowChangesTest {
    private fun values(vararg distinct: String, more: Boolean = false, sequence: List<String> = distinct.toList(), opaque: Set<String> = emptySet()) =
        ProbeValues(distinct.toList(), opaque, more, sequence)

    @Test
    fun differentValuesAreAChange() {
        val change = ShowChanges.compare(values("3"), values("0"))
        assertEquals(Change.CHANGED, change.verdict)
        assertEquals("original: 3 → mutant: 0", change.text)
    }

    @Test
    fun moreValuesThanKeptAreElided() {
        val change = ShowChanges.compare(values("1", "2", "3", more = true), values("0"))
        assertEquals("original: 1, 2, 3, … → mutant: 0", change.text)
    }

    @Test
    fun sameValuesAreLikelyEquivalent() {
        val change = ShowChanges.compare(values("true", "false"), values("true", "false"))
        assertEquals(Change.SAME, change.verdict)
        assertEquals("same values observed — no test input makes the mutant differ (a missing case, or equivalent)", change.text)
    }

    @Test
    fun sameValuesInAnotherOrderAreAChange() {
        val change = ShowChanges.compare(values("true", "false"), values("true", "false", sequence = listOf("true", "true", "false")))
        assertEquals(Change.CHANGED, change.verdict)
        assertEquals("evaluation 2: false → true", change.firstDifference)
    }

    @Test
    fun opaqueValuesOnlyCompareTypes() {
        val change = ShowChanges.compare(values("<Player>", opaque = setOf("<Player>")), values("<Player>", opaque = setOf("<Player>")))
        assertEquals(Change.TYPE_ONLY, change.verdict)
    }

    @Test
    fun aSideThatSawNothingWasNotReached() {
        assertEquals("original: (not reached) → mutant: 1", ShowChanges.compare(values(), values("1")).text)
        assertEquals(Change.NOT_CAPTURED, ShowChanges.compare(values(), values()).verdict)
        assertEquals(Change.NOT_CAPTURED, ShowChanges.compare(null, values("1")).verdict)
    }

    @Test
    fun readsAndDeduplicatesTheProbeFile(@TempDir dir: File) {
        // Two copies of the probe (a Robolectric sandbox and the system loader) wrote to one file.
        val file = File(dir, "probe.values").apply {
            writeText("SV\t1\nDV\t1\nSV\t2\nDV\t2\nSO\t<Foo>\nDO\t<Foo>\nDV\t1\nDV\t9\n")
        }
        val read = ProbeValues.read(file)
        assertEquals(listOf("1", "2", "<Foo>"), read.distinct)
        assertTrue(read.more)
        assertEquals(setOf("<Foo>"), read.opaque)
        assertEquals(listOf("1", "2", "<Foo>"), read.sequence)
        assertTrue(ProbeValues.read(File(dir, "missing")).isEmpty)
    }

    @Test
    fun theChangeRoundTripsThroughTheReport(@TempDir dir: File) {
        val change = Change(Change.CHANGED, listOf("3"), listOf("0"), "original: 3 → mutant: 0")
        val json = linkedMapOf(
            "summary" to mapOf("total" to 1, "wallMillis" to 1, "killed" to 0, "valid" to 1, "covered" to 1),
            "mutants" to listOf(
                mapOf(
                    "id" to 1, "file" to "A.kt", "line" to 1, "column" to 1, "operator" to "RETURN_VALUE",
                    "description" to "return n → return 0", "status" to "SURVIVED", "change" to change.toJson(),
                ),
            ),
        )
        val file = File(dir, "report.json").apply { writeText(JsonOutput.toJson(json)) }
        assertEquals(change, ReportReader.read(file).mutants.single().change)
        assertNull(ReportReader.read(fixtureReport(dir)).mutants.first().change)
    }

    private fun report(change: Change?) = nastyReport().let { r -> r.copy(mutants = r.mutants.map { it.copy(change = change) }) }

    @Test
    fun theTerminalPrintsWhatChanged(@TempDir dir: File) {
        val text = TerminalReportWriter.write(report(Change(Change.CHANGED, text = "original: 3 → mutant: 0")), dir, color = false, reportFile = File(dir, "r.json"))
        assertTrue(text.contains("      what changed: original: 3 → mutant: 0\n"), text)
        assertFalse(TerminalReportWriter.write(report(null), dir, color = false, reportFile = File(dir, "r.json")).contains("what changed"))
    }

    @Test
    fun theHtmlEscapesWhatChanged(@TempDir dir: File) {
        val text = HtmlReportWriter.write(report(Change(Change.CHANGED, text = "original: \"<b>\" → mutant: \"&\"")), dir)
        assertTrue(text.contains("What changed: original: &quot;&lt;b&gt;&quot; → mutant: &quot;&amp;&quot;"), text)
        assertFalse(text.contains("\"<b>\""))
    }
}
