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
 * A fixture with a test that kills nothing, a redundant pair of tests, and a test that is the only one
 * to kill its mutant (issue #14): `AddTest`/`AddRedundantTest` both kill `add`'s mutant, `MulTest` alone
 * kills `mul`'s mutant, and `UselessTest` covers both but asserts nothing that depends on their result.
 */
class TestValueFunctionalTest {
    private val main = mapOf(
        "Calc.kt" to """
            fun add(a: Int, b: Int) = a + b
            fun mul(a: Int, b: Int) = a * b
        """.trimIndent(),
    )
    private val test = mapOf(
        "AddTest.kt" to "class AddTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }",
        "AddRedundantTest.kt" to "class AddRedundantTest { @kotlin.test.Test fun addsToo() = kotlin.test.assertEquals(5, add(2, 3)) }",
        "MulTest.kt" to "class MulTest { @kotlin.test.Test fun muls() = kotlin.test.assertEquals(6, mul(2, 3)) }",
        "UselessTest.kt" to """
            class UselessTest {
                @kotlin.test.Test fun coversButAssertsNothing() {
                    add(2, 3)
                    mul(2, 3)
                    kotlin.test.assertTrue(true)
                }
            }
        """.trimIndent(),
    )

    @Test
    fun `with killMatrix, reports redundant, unique and minimal tests`(@TempDir dir: File) {
        writeProject(dir, main = main, test = test, krispr = "killMatrix.set(true)")

        val result = run(dir, "krisprRun")

        assertTrue("\"killMatrix\": true" in report(dir), report(dir))
        assertTrue("Test value:" in result.output, result.output)
        assertTrue(Regex("UselessTest\\.\\w+: covers \\d+, kills 0").containsMatchIn(result.output), result.output)
        assertTrue(
            "AddTest.adds — also killed by AddRedundantTest.addsToo" in result.output ||
                "AddRedundantTest.addsToo — also killed by AddTest.adds" in result.output,
            result.output,
        )
        assertTrue(Regex("minimal set killing the same \\d+ mutants: 2 of 3 tests").containsMatchIn(result.output), result.output)
        assertTrue("MulTest" in result.output, result.output)
    }

    @Test
    fun `without killMatrix, only first-kill evidence is reported`(@TempDir dir: File) {
        writeProject(dir, main = main, test = test)

        val result = run(dir, "krisprRun")

        assertFalse("\"killMatrix\": true" in report(dir), report(dir))
        assertTrue("partial: only first-kill data" in result.output, result.output)
        assertFalse("also killed by" in result.output, result.output)
        assertFalse("minimal set killing" in result.output, result.output)
    }

    private fun report(dir: File) = dir.resolve("build/krispr/report.json").readText()

    private fun run(dir: File, vararg arguments: String): BuildResult = runner(dir, *arguments).build()

    private fun runner(dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*arguments, "--max-workers=2", "--stacktrace")

    private fun writeProject(
        dir: File,
        main: Map<String, String> = emptyMap(),
        test: Map<String, String> = emptyMap(),
        krispr: String = "",
    ) {
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
                kotlin("jvm") version "$KOTLIN"
                id("dev.krispr")
            }
            kotlin { jvmToolchain(21) }
            dependencies { testImplementation(kotlin("test")) }
            tasks.test { useJUnitPlatform() }
            krispr {
                threads.set(2)
                $krispr
            }
            """.trimIndent(),
        )
        for ((name, source) in main) dir.resolve("src/main/kotlin/$name").apply { parentFile.mkdirs() }.writeText(source)
        for ((name, source) in test) dir.resolve("src/test/kotlin/$name").apply { parentFile.mkdirs() }.writeText(source)
    }

    private companion object {
        /** The Kotlin version krispr's compiler plugin is built against. */
        const val KOTLIN = "2.4.20"
    }
}
