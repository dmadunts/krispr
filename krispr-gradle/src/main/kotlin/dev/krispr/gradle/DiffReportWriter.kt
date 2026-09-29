package dev.krispr.gradle

import groovy.json.JsonOutput

/**
 * PR-comment output for diff mode, scoped to whatever [Report] diff mode already narrowed the run to (see
 * [KrisprRunTask.since]): a GitHub-flavoured markdown summary (`diff.md`), meant to be posted as a PR
 * comment, and GitHub check-run style line annotations (`diff-annotations.json`), meant for inline comments.
 * krispr makes no GitHub API calls itself; a CI workflow reads these files and does the posting (see
 * docs/diff-mode.md).
 */
internal object DiffReportWriter {
    private const val DEFAULT_CAP = 20

    fun writeMarkdown(report: Report, cap: Int = DEFAULT_CAP): String {
        val survivors = sortedSurvivors(report)
        val builder = StringBuilder()
        builder.append("## krispr diff report\n\n")
        builder.append("${report.mutants.size} mutant(s) on changed lines, ${survivors.size} survived.\n\n")
        if (survivors.isEmpty()) return builder.toString()

        builder.append("### Survivors\n\n")
        val shown = survivors.take(cap)
        for ((file, inFile) in shown.groupBy { it.file }) {
            builder.append("**").append(codeSpan(file)).append("**\n\n")
            for (mutant in inFile) {
                builder.append("- Line ").append(mutant.line).append(": ").append(codeSpan(mutant.plainEnglish))
                    .append(" (").append(codeSpan(mutant.original)).append(" → ").append(codeSpan(mutant.mutated)).append(")\n")
            }
            builder.append('\n')
        }
        if (survivors.size > cap) builder.append("_+${survivors.size - cap} more in the full report_\n\n")
        return builder.toString().trimEnd('\n') + "\n"
    }

    /** GitHub check-run annotation shape: `path`, `start_line`, `end_line`, `annotation_level`, `message`. */
    fun writeAnnotations(report: Report): String {
        val annotations = sortedSurvivors(report).map { mutant ->
            linkedMapOf(
                "path" to mutant.file,
                "start_line" to mutant.line,
                "end_line" to mutant.line,
                "annotation_level" to "warning",
                "message" to mutant.plainEnglish,
            )
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(annotations)) + "\n"
    }

    private fun sortedSurvivors(report: Report): List<MutantReport> =
        report.survivors.sortedWith(compareBy({ it.file }, { it.line }, { it.column }, { it.id }))
}
