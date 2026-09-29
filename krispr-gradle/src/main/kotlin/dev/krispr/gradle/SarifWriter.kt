package dev.krispr.gradle

import groovy.json.JsonOutput
import java.io.File
import java.net.URI

/**
 * SARIF 2.1.0 (https://docs.oasis-open.org/sarif/sarif/v2.1.0), one `note`-level result per survivor, so
 * GitHub code scanning shows a line annotation on it. Killed, not-measured and other statuses are not
 * findings and are left out; the HTML report and `report.json` have the full picture.
 */
internal object SarifWriter {
    private const val SCHEMA_URI = "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"

    fun write(report: Report): String {
        val survivors = report.survivors.sortedWith(compareBy({ it.file }, { it.line }, { it.column }, { it.id }))
        val rules = survivors.map { it.operator }.distinct().sorted()
        val root = linkedMapOf(
            "\$schema" to SCHEMA_URI,
            "version" to "2.1.0",
            "runs" to listOf(
                linkedMapOf(
                    "tool" to linkedMapOf(
                        "driver" to linkedMapOf(
                            "name" to "krispr",
                            "rules" to rules.map { operator -> rule(operator) },
                        ),
                    ),
                    "results" to survivors.map { result(it) },
                ),
            ),
        )
        return JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n"
    }

    /** Percent-encodes whatever the file name isn't valid in a URI path, so schema-strict SARIF consumers accept it. */
    private fun uri(file: String): String = URI(null, null, file.replace(File.separatorChar, '/'), null, null).toASCIIString()

    private fun rule(operator: String) = linkedMapOf(
        "id" to operator,
        "shortDescription" to linkedMapOf("text" to "krispr $operator mutant survived: no test told it apart from the original code."),
    )

    private fun result(mutant: MutantReport) = linkedMapOf(
        "ruleId" to mutant.operator,
        "level" to "note",
        "message" to linkedMapOf("text" to "No test fails if `${mutant.original}` becomes `${mutant.mutated}`."),
        "locations" to listOf(
            linkedMapOf(
                "physicalLocation" to linkedMapOf(
                    "artifactLocation" to linkedMapOf("uri" to uri(mutant.file)),
                    "region" to linkedMapOf("startLine" to mutant.line, "startColumn" to mutant.column),
                ),
            ),
        ),
    )
}
