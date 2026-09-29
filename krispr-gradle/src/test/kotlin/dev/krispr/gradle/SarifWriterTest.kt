package dev.krispr.gradle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SarifWriterTest {
    private val mapper = ObjectMapper()

    @Test
    fun producesOneNoteResultPerSurvivorValidAgainstTheSarifSchema(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val sarif = SarifWriter.write(report)

        val schemaStream = requireNotNull(javaClass.getResourceAsStream("/sarif-schema-2.1.0.json")) {
            "sarif-schema-2.1.0.json not found on the test classpath"
        }
        val schema = schemaStream.use {
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.readTree(it))
        }
        val node = mapper.readTree(sarif)
        val errors = schema.validate(node)
        assertTrue(errors.isEmpty(), "SARIF failed schema validation: $errors")

        assertEquals("2.1.0", node["version"].asText())
        val results = node["runs"][0]["results"]
        assertEquals(report.survivors.size, results.size())
        for (result in results) {
            assertEquals("note", result["level"].asText())
        }
    }

    @Test
    fun locationsUseForwardSlashSeparatedRelativePaths(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val sarif = SarifWriter.write(report)
        val node = mapper.readTree(sarif)
        val firstUri = node["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText()
        assertEquals("src/main/kotlin/Foo.kt", firstUri)
    }

    @Test
    fun producesValidJsonAndSchemaWhenFileAndDescriptionAreHostile() {
        val sarif = SarifWriter.write(nastyReport())

        val schemaStream = requireNotNull(javaClass.getResourceAsStream("/sarif-schema-2.1.0.json")) {
            "sarif-schema-2.1.0.json not found on the test classpath"
        }
        val schema = schemaStream.use {
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.readTree(it))
        }
        val node = mapper.readTree(sarif)
        val errors = schema.validate(node)
        assertTrue(errors.isEmpty(), "SARIF failed schema validation: $errors")

        val message = node["runs"][0]["results"][0]["message"]["text"].asText()
        assertTrue(message.contains(NASTY_ORIGINAL), message)
        assertTrue(message.contains(NASTY_MUTATED), message)
    }

    @Test
    fun rulesArrayIsDistinctOperatorsAmongSurvivorsOnly(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val sarif = SarifWriter.write(report)
        val node = mapper.readTree(sarif)
        val ruleIds = node["runs"][0]["tool"]["driver"]["rules"].map { it["id"].asText() }.toSet()
        val survivorOperators = report.survivors.map { it.operator }.toSet()
        assertEquals(survivorOperators, ruleIds)
    }
}
