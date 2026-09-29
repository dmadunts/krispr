package dev.krispr.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Turns `build/krispr/report.json` into the human-facing outputs: a browsable HTML report, a PR-comment
 * markdown summary, and a SARIF file for code-scanning line annotations. Registered `finalizedBy` on
 * `krisprRun` (see KrisprGradlePlugin), so it always runs right after, whether or not the run task's own
 * outcome contains survivors.
 */
abstract class KrisprReportTask : DefaultTask() {
    /**
     * Internal rather than an input file: `krisprRun` always writes it before this task runs, except when
     * `krisprRun` itself failed before doing so, which this task tolerates by writing nothing.
     */
    @get:Internal abstract val report: RegularFileProperty
    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:OutputFile abstract val html: RegularFileProperty
    @get:OutputFile abstract val prSummary: RegularFileProperty
    @get:OutputFile abstract val sarif: RegularFileProperty

    /** Written only when the run was diff-scoped (see [ReportSummary.diffBase]); a PR-comment markdown summary. */
    @get:OutputFile abstract val diffMarkdown: RegularFileProperty

    /** Written only when the run was diff-scoped; GitHub check-run style line annotations. */
    @get:OutputFile abstract val diffAnnotations: RegularFileProperty

    init {
        // Depends only on report.json's content, which krisprRun (never up-to-date, see KrisprRunTask)
        // rewrites on every run; recomputing this from it is cheap, so keep it just as fresh.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val reportFile = report.get().asFile
        if (!reportFile.isFile) {
            logger.lifecycle("krispr: no report.json at $reportFile; krisprReport has nothing to read.")
            return
        }
        if (ReportReader.isExtremeMode(reportFile)) {
            logger.lifecycle("krispr: extreme-mode report has no score or survivors; the pseudo-tested list is in $reportFile.")
            return
        }
        val parsed = ReportReader.read(reportFile)
        writeFile(html.get().asFile, HtmlReportWriter.write(parsed, projectDirectory.get().asFile))
        writeFile(prSummary.get().asFile, PrSummaryWriter.write(parsed))
        writeFile(sarif.get().asFile, SarifWriter.write(parsed))
        val extra = if (parsed.summary.diffBase != null) {
            writeFile(diffMarkdown.get().asFile, DiffReportWriter.writeMarkdown(parsed))
            writeFile(diffAnnotations.get().asFile, DiffReportWriter.writeAnnotations(parsed))
            ", ${diffMarkdown.get().asFile} and ${diffAnnotations.get().asFile}"
        } else {
            ""
        }
        logger.lifecycle("krispr: wrote ${html.get().asFile}, ${prSummary.get().asFile} and ${sarif.get().asFile}$extra")
    }

    private fun writeFile(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
