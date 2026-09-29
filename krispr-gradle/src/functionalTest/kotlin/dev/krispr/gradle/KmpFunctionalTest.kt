package dev.krispr.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Builds `kotlin("multiplatform")` projects with a JVM and a JS target (no Android SDK needed) and checks
 * that `krisprRun` mutates `commonMain` through the JVM target and runs `commonTest` against it.
 */
class KmpFunctionalTest {

    @Test
    fun `a good commonTest kills a commonMain mutant and a weak one lets it survive, with the configuration cache`(@TempDir dir: File) {
        writeProject(dir, targets = "jvm()\n    js(IR) { nodejs() }")

        // A normal build compiles every target without instrumenting any of them.
        val normal = run(dir, "compileKotlinJvm", "compileKotlinJs", "--configuration-cache")
        assertFalse(dir.resolve("build/krispr").exists(), normal.output)
        val plain = classFiles(dir.resolve("build/classes"))
        assertTrue(plain.any { it.name == "GradeKt.class" }, "no GradeKt.class in the normal build:\n${normal.output}")
        assertTrue(plain.none { it.readBytes().contains(RUNTIME) }, "the normal build references the krispr runtime")

        val store = run(dir, "krisprRun", "--configuration-cache")
        assertTrue("Configuration cache entry stored" in store.output, store.output)
        assertStatuses(dir, store)

        // Reused history skips the forks, so turn it off to prove the reused entry still instruments and runs.
        dir.resolve("build/krispr/history.json").delete()
        val reuse = run(dir, "krisprRun", "--configuration-cache")
        assertTrue("Configuration cache entry reused" in reuse.output, reuse.output)
        assertStatuses(dir, reuse)
    }

    @Test
    fun `a multiplatform module without a JVM-hosted target fails and says why`(@TempDir dir: File) {
        writeProject(dir, targets = "js(IR) { nodejs() }", jvmActual = false)

        val result = runner(dir, "krisprRun").buildAndFail()

        assertTrue("needs a JVM or Android target (targets: js)" in result.output, result.output)
        assertTrue("native, JS and Wasm test runs are not supported" in result.output, result.output)
    }

    /** The strong test kills `passed`'s boundary mutant, the weak one misses `grade`'s; all attributed to commonMain. */
    private fun assertStatuses(dir: File, result: BuildResult) {
        val mutants = mutants(dir.resolve("build/krispr/report.json").readText())
        assertTrue(mutants.isNotEmpty(), result.output)
        val files = mutants.map { it.getValue("file") }.toSet()
        assertEquals(setOf(GRADE, "src/jvmMain/kotlin/demo/Host.jvm.kt"), files, result.output)

        fun status(line: Int, description: String) =
            mutants.singleOrNull { it["file"] == GRADE && it["line"] == "$line" && description in it.getValue("description") }
                ?.get("status") ?: error("no mutant '$description' on $GRADE:$line in\n$mutants")
        assertEquals("KILLED", status(3, "score > 50"))
        assertEquals("SURVIVED", status(6, "score > 90"))
        assertTrue(mutants.none { it["status"] == "RUN_ERROR" }, mutants.toString())
    }

    private fun run(dir: File, vararg arguments: String): BuildResult = runner(dir, *arguments).build()

    private fun runner(dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*arguments, "--max-workers=2", "--stacktrace")

    private fun writeProject(dir: File, targets: String, jvmActual: Boolean = true) {
        val repo = System.getProperty("krispr.repo").replace("\\", "/")
        val version = System.getProperty("krispr.version")
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven {
                        url = uri("$repo")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    mavenCentral()
                    gradlePluginPortal()
                }
                resolutionStrategy.eachPlugin {
                    if (requested.id.id == "dev.krispr") useModule("dev.krispr:krispr-gradle:$version")
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven {
                        url = uri("$repo")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    mavenCentral()
                }
            }
            rootProject.name = "demo"
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "$KOTLIN"
                id("dev.krispr")
            }
            kotlin {
                jvmToolchain(21)
                $targets
                sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
            }
            krispr { threads.set(2) }
            """.trimIndent(),
        )
        // Line numbers matter: the assertions name lines 3 and 6.
        source(
            dir, "commonMain", "Grade.kt",
            """
            package demo

            fun passed(score: Int): Boolean = score >= 50

            fun grade(score: Int): String = when {
                score >= 90 -> "A"
                score >= 70 -> "B"
                else -> "C"
            }

            expect fun host(): String
            """,
        )
        source(dir, "jvmMain", "Host.jvm.kt", "package demo\n\nactual fun host(): String = if (System.getProperty(\"java.vendor\") != null) \"jvm\" else \"?\"\n")
        source(dir, "jsMain", "Host.js.kt", "package demo\n\nactual fun host(): String = \"js\"\n")
        source(
            dir, "commonTest", "GradeTest.kt",
            """
            package demo

            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertFalse
            import kotlin.test.assertTrue

            class GradeTest {
                @Test
                fun passesFromFifty() {
                    assertTrue(passed(50))
                    assertFalse(passed(49))
                }

                // Weak on purpose: no score on a grade boundary.
                @Test
                fun grades() {
                    assertEquals("A", grade(95))
                    assertEquals("C", grade(10))
                    assertTrue(host().isNotEmpty())
                }
            }
            """,
        )
        if (!jvmActual) dir.resolve("src/jvmMain").deleteRecursively()
    }

    private fun source(dir: File, sourceSet: String, name: String, text: String) {
        dir.resolve("src/$sourceSet/kotlin/demo/$name").apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
    }

    private fun classFiles(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()

    private fun ByteArray.contains(bytes: ByteArray): Boolean =
        (0..size - bytes.size).any { start -> bytes.indices.all { this[start + it] == bytes[it] } }

    /** Each mutant object of report.json as its scalar fields; objects are flat apart from the `tests` array. */
    private fun mutants(report: String): List<Map<String, String>> =
        Regex("\\{[^{}]*\"line\"[^{}]*\\}").findAll(report).map { match ->
            Regex("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+)").findAll(match.value)
                .associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
        }.toList()

    companion object {
        private const val KOTLIN = "2.4.20"
        private const val GRADE = "src/commonMain/kotlin/demo/Grade.kt"
        private val RUNTIME = "dev/krispr/runtime".toByteArray()
    }
}
