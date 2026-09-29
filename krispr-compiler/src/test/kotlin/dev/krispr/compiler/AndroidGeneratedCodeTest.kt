package dev.krispr.compiler

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Code that Android builds put next to user code: annotation-processor output and Compose's IR rewrite.
 * Composables are arid by default (see AridCodeTest), so the Compose tests opt back in.
 */
class AndroidGeneratedCodeTest {
    private val composable = """
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.remember

        @Composable
        fun Badge(count: Int, label: String = "n", limit: Int = 99) {
            val shown = remember(count) { count + 1 }
            val text = if (shown > limit) "99+" else label + shown
            Text(text)
            if (count == 0) Text("none")
        }

        @Composable
        fun Text(value: String) {}
    """.trimIndent()

    /** The same user code without Compose: the mutants the Composable version must get, and no more. */
    private val plain = composable.replace("@Composable", "").replace(
        "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.remember",
        "fun <T> remember(key: Any?, calc: () -> T): T = calc()",
    )

    private val userMutants = listOf(
        "Text(\"none\") → (removed)",
        "Text(text) → (removed)",
        "count + 1 → count - 1",
        // `if (count == 0) Text("none")` forced false would repeat the removal of `Text("none")`.
        "count == 0 → count != 0",
        "if (count == 0) → if (true)",
        "if (shown > limit) → if (false)",
        "if (shown > limit) → if (true)",
        "return count + 1 → return 0",
        "shown > limit → shown >= limit",
    )

    /**
     * The Gradle plugin passes `-Xcompiler-plugin-order=dev.krispr>…compose…` whenever the Compose
     * compiler plugin is applied; krispr then mutates the user's code before Compose wraps it.
     */
    @Test
    fun krisprRunsBeforeComposeSoInjectedComposerLogicGetsNoMutants() {
        // Older compilers order plugins by classpath alone; the next test covers Compose running first.
        assumeTrue(KotlinCompilerVersion.VERSION >= "2.3", "-Xcompiler-plugin-order arrived in Kotlin 2.3.0")
        assertEquals(userMutants.sorted(), Harness.compile(plain).mutants.map { it.description }.sorted())

        val compiled = Harness.compile(
            composable,
            cliPlugins = listOf(composeJar),
            mutate = setOf("composables"),
            kotlincArguments = listOf("-Xcompiler-plugin-order=${KrisprCommandLineProcessor.PLUGIN_ID}>$COMPOSE_PLUGIN_ID"),
            captureIr = true,
        )
        // Compose did rewrite the function afterwards: it gained `${'$'}composer` and `${'$'}changed`...
        val badge = compiled.classLoader.loadClass("SampleKt").declaredMethods.single { it.name == "Badge" }
        assertTrue(badge.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" })
        // ...but only after krispr was done with it.
        assertFalse("\$composer" in compiled.ir, "krispr saw Compose output")
        assertEquals(userMutants.sorted(), compiled.mutants.map { it.description }.sorted())
    }

    /**
     * Defence in depth: if Compose ran first, its groups, `${'$'}changed` bit tests and default masks carry
     * no source offsets or operator origins, so krispr still leaves them alone.
     */
    @Test
    fun composeInjectedLogicGetsNoMutantsEvenWhenComposeRunsFirst() {
        val compiled = Harness.compile(composable, cliPlugins = listOf(composeJar), captureIr = true, mutate = setOf("composables"))
        assertTrue("\$changed" in compiled.ir && "\$composer" in compiled.ir, "Compose did not run first")
        assertEquals(userMutants.sorted(), compiled.mutants.map { it.description }.sorted())
    }

    @Test
    fun sourcesUnderExcludedDirectoriesGetNoMutants() {
        val compiled = Harness.compile(
            "fun user(a: Int, b: Int): Int = a + b",
            extraSources = mapOf(
                "build/generated/ksp/debug/kotlin/FooJsonAdapter.kt" to "fun generated(a: Int, b: Int): Boolean = a + b > 0 && a != b",
            ),
            excludedDirs = listOf("build"),
        )
        assertEquals(listOf("a + b → a - b", "return a + b → return 0"), compiled.mutants.map { it.description }.sorted())
        assertTrue(compiled.mutants.none { "FooJsonAdapter" in it.file }, compiled.mutants.toString())
    }

    /** Listed ahead of krispr, so without a constraint Compose's IR extension runs first. */
    private val composeJar = java.io.File(ComposePluginRegistrar::class.java.protectionDomain.codeSource.location.toURI())

    private companion object {
        const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"
    }
}
