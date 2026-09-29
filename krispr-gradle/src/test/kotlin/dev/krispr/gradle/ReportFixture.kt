package dev.krispr.gradle

import java.io.File

/** Copies the shared `fixture-report.json` resource (a KrisprRunTask.writeReport-shaped file) into [dir]. */
internal fun fixtureReport(dir: File): File {
    val file = File(dir, "report.json")
    val resource = requireNotNull(ReportReader::class.java.getResourceAsStream("/dev/krispr/gradle/fixture-report.json")) {
        "fixture-report.json not found on the test classpath"
    }
    resource.use { input -> file.outputStream().use { input.copyTo(it) } }
    return file
}

/** Strings a real mutant could carry that are hostile to HTML, Markdown or JSON if left unescaped. */
internal const val NASTY_FILE = "src/<script>/a&b\"c'd`e.kt"
internal const val NASTY_ORIGINAL = "<script>alert(1)</script> & \"q'q\" `bt` | * _ [x](y)\nline2"
internal const val NASTY_MUTATED = "<b>bold</b> & \"m\" `bt2``` | * _ [z](w)\nline2b"
internal const val NASTY_KILLED_BY = "Foo\"Test`<script>|*_[x](y)\nmethod"
internal const val NASTY_REASON = "reason with <tag> & \"q\" `bt` [x](y)\nnewline"

/** A single-survivor [Report] whose every string field is [NASTY_FILE]/[NASTY_ORIGINAL]/etc. */
internal fun nastyReport(): Report = Report(
    summary = ReportSummary(
        total = 1, wallMillis = 1, killed = 0, valid = 1, covered = 1,
        mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 1),
    ),
    mutants = listOf(
        MutantReport(
            id = 1, file = NASTY_FILE, line = 1, column = 1, operator = "CONDITIONALS_BOUNDARY",
            description = "$NASTY_ORIGINAL → $NASTY_MUTATED", status = MutantStatus.SURVIVED,
            tests = listOf(NASTY_KILLED_BY), killedBy = NASTY_KILLED_BY, millis = 1, runner = null,
            reason = NASTY_REASON,
        ),
    ),
    maxSurvivorsPerFile = 3,
)
