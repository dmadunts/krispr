package dev.krispr.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilerArtifactTest {
    @Test
    fun picksTheVariantForEachSupportedRange() {
        assertEquals("krispr-compiler-k2120", CompilerArtifact.artifactId("2.1.20"))
        assertEquals("krispr-compiler-k2120", CompilerArtifact.artifactId("2.1.25"))
        assertEquals("krispr-compiler-k220", CompilerArtifact.artifactId("2.2.0"))
        assertEquals("krispr-compiler-k220", CompilerArtifact.artifactId("2.2.21"))
        assertEquals("krispr-compiler-k230", CompilerArtifact.artifactId("2.3.0"))
        assertEquals("krispr-compiler-k2320", CompilerArtifact.artifactId("2.3.20"))
        assertEquals("krispr-compiler-k240", CompilerArtifact.artifactId("2.4.0"))
        assertEquals("krispr-compiler-k240", CompilerArtifact.artifactId("2.4.20"))
    }

    @Test
    fun treatsPreReleasesAsTheReleaseTheyPrecede() {
        assertEquals("krispr-compiler-k240", CompilerArtifact.artifactId("2.4.0-Beta1"))
        assertEquals("krispr-compiler-k2320", CompilerArtifact.artifactId("2.3.20-RC2"))
    }

    @Test
    fun failsWithASupportedRangeMessageBelowTheOldestSupportedRelease() {
        val exception = assertThrows(GradleException::class.java) { CompilerArtifact.artifactId("2.0.0") }
        assertTrue(exception.message!!.contains("2.1.20"))
        assertTrue(exception.message!!.contains("2.0.0"))
    }

    @Test
    fun failsWithASupportedRangeMessageAtOrAboveTheUnsupportedRelease() {
        val exception = assertThrows(GradleException::class.java) { CompilerArtifact.artifactId("2.5.0") }
        assertTrue(exception.message!!.contains("2.5.0"))
    }

    @Test
    fun findsTheCompilerEmbeddableArtifactAmongResolvedArtifacts() {
        val artifacts = listOf(
            Triple("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.10"),
            Triple("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.10"),
            Triple("other.group", "kotlin-compiler-embeddable", "9.9.9"),
        )
        assertEquals("2.3.10", CompilerArtifact.compilerEmbeddableVersion(artifacts))
    }

    @Test
    fun returnsNullWhenNoCompilerEmbeddableArtifactIsPresent() {
        val artifacts = listOf(Triple("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.10"))
        assertEquals(null, CompilerArtifact.compilerEmbeddableVersion(artifacts))
    }
}
