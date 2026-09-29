package dev.krispr.gradle

/**
 * GitHub-flavoured markdown for a PR comment: the headline covered score, then survivors grouped by file
 * in line order, each worded as a test someone could write (docs/PHILOSOPHY.md, "What a mutation score
 * measures"), capped at [Report.maxSurvivorsPerFile] per file.
 */
internal object PrSummaryWriter {
    fun write(report: Report): String {
        val summary = report.summary
        val builder = StringBuilder()
        builder.append("## krispr report\n\n")
        builder.append(headline(summary))
        builder.append("\n\n")

        val survivors = report.survivors.sortedWith(compareBy({ it.file }, { it.line }, { it.column }, { it.id }))
        if (survivors.isEmpty()) {
            builder.append("No surviving mutants.\n")
            return builder.toString()
        }

        builder.append("### Survivors\n\n")
        val cap = report.maxSurvivorsPerFile.takeIf { it > 0 } ?: Int.MAX_VALUE
        for ((file, inFile) in survivors.groupBy { it.file }) {
            builder.append("**").append(codeSpan(file)).append("**\n\n")
            for (mutant in inFile.take(cap)) {
                builder.append("- ").append(testGoal(mutant)).append('\n')
            }
            if (inFile.size > cap) {
                builder.append("- _+${inFile.size - cap} more in the full report_\n")
            }
            builder.append('\n')
        }
        return builder.toString().trimEnd('\n') + "\n"
    }

    private fun headline(summary: ReportSummary): String {
        val covered = summary.coveredScore?.let { "$it%" } ?: "n/a"
        val valid = summary.mutationScore?.let { "$it%" } ?: "n/a"
        val notMeasured = summary.counts[MutantStatus.NOT_MEASURED] ?: 0
        val extra = if (notMeasured > 0) " ($notMeasured not measured)" else ""
        return "**$covered** of covered mutants killed ($valid of valid$extra)."
    }

    private fun testGoal(mutant: MutantReport): String =
        "No test fails if ${codeSpan(mutant.original)} becomes ${codeSpan(mutant.mutated)} " +
            "at ${codeSpan(mutant.file)}:${mutant.line}"
}
