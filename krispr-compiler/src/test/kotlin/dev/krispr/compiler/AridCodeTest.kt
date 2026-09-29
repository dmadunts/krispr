package dev.krispr.compiler

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Arid code gets no mutants unless the build opts back in, one category at a time. Every source keeps
 * one ordinary function, `logic`, whose mutants every rule must leave in place. Library stubs live under
 * an excluded `stubs/` directory so they get no mutants of their own.
 */
class AridCodeTest {
    private val logic = "fun logic(a: Int): Int = a + 1"
    private val logicMutants = listOf("a + 1 → a - 1", "return a + 1 → return 0")

    private fun descriptions(source: String, extraSources: Map<String, String> = emptyMap(), mutate: Set<String> = emptySet()): List<String> =
        Harness.compile("$source\n$logic", extraSources, excludedDirs = listOf("stubs"), mutate = mutate).mutants.map { it.description }.sorted()

    /** [always] are the mutants the source keeps either way; [mutated] only with `mutate=<category>`. */
    private fun assertArid(
        category: String,
        source: String,
        extraSources: Map<String, String> = emptyMap(),
        always: List<String> = emptyList(),
        mutated: List<String>,
    ) {
        assertEquals((logicMutants + always).sorted(), descriptions(source, extraSources), "skipped by default")
        assertEquals((logicMutants + always + mutated).sorted(), descriptions(source, extraSources, setOf(category)), "mutate=$category")
    }

    private val composeStubs = mapOf(
        "stubs/Preview.kt" to "package androidx.compose.ui.tooling.preview\nannotation class Preview",
    )

    @Test
    fun composableFunctionsAndTheLambdasPassedToComposablesInsideThem() = assertArid(
        "composables",
        """
        import androidx.compose.runtime.Composable

        @Composable
        fun Counter(count: Int) {
            Button(onClick = { record(count > 9) }) { Label(if (count == 0) "none" else "some") }
        }

        fun record(value: Boolean) {}
        @Composable fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {}
        @Composable fun Label(text: String) {}
        """.trimIndent(),
        mutated = listOf(
            "if (count == 0) → if (true)", "if (count == 0) → if (false)", "count > 9 → count >= 9",
            "Label(if (count == 0) \"none\" else \"some\") → (removed)", "record(count > 9) → (removed)",
        ),
    )

    @Test
    fun composableLambdasOutsideComposables() = assertArid(
        "composables",
        """
        import androidx.compose.runtime.Composable

        fun setContent(content: @Composable () -> Unit) {}

        fun show(count: Int) {
            setContent { Label(count - 1) }
        }

        @Composable fun Label(value: Int) {}
        """.trimIndent(),
        mutated = listOf("count - 1 → count + 1", "Label(count - 1) → (removed)"),
    )

    @Test
    fun composablesAreSkippedWithTheComposeCompilerApplied() {
        val source = """
            import androidx.compose.runtime.Composable

            @Composable
            fun Badge(count: Int) { if (count > 99) Label(count + 1) }

            @Composable fun Label(value: Int) {}
            $logic
        """.trimIndent()
        val compiled = Harness.compile(source, otherPlugins = listOf(ComposePluginRegistrar()))
        assertEquals(logicMutants.sorted(), compiled.mutants.map { it.description }.sorted())
    }

    @Test
    fun previewsAndMultipreviews() = assertArid(
        "composables",
        """
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        annotation class PreviewBothThemes

        @Preview fun LabelPreview(): Int = 3 * 2
        @PreviewBothThemes fun BadgePreview(): Long = 4L - 2L
        """.trimIndent(),
        composeStubs,
        mutated = listOf("3 * 2 → 3 / 2", "4L - 2L → 4L + 2L", "return 3 * 2 → return 0").sorted(),
    )

    @Test
    fun loggingCallsAndTheirArguments() = assertArid(
        "logging",
        """
        import io.github.oshai.kotlinlogging.KLogger
        import org.slf4j.Logger
        import timber.log.Timber

        fun logs(n: Long, slf4j: Logger, klogger: KLogger) {
            android.util.Log.d("tag", "n=" + (n + 1L))
            Timber.d("n=" + (n + 2L))
            Timber.tag("t").w("n=" + (n + 3L))
            slf4j.info("n={}", n + 4L)
            klogger.info { n + 5L }
            println(n + 6L)
            logVerbose(n + 7L)
        }

        fun logVerbose(value: Long) {}
        """.trimIndent(),
        mapOf(
            "stubs/Log.kt" to "package android.util\nobject Log { fun d(tag: String, msg: String): Int = 0 }",
            "stubs/Timber.kt" to """
                package timber.log
                class Timber { open class Tree { fun w(message: String) {} }
                    companion object Forest : Tree() { fun d(message: String) {}; fun tag(tag: String): Tree = this } }
            """.trimIndent(),
            "stubs/Slf4j.kt" to "package org.slf4j\ninterface Logger { fun info(format: String, arg: Any?) }",
            "stubs/KLogger.kt" to "package io.github.oshai.kotlinlogging\ninterface KLogger { fun info(message: () -> Any?) }",
        ),
        mutated = (1..7).map { "n + ${it}L → n - ${it}L" } + listOf(
            "Timber.d(\"n=\" + (n + 2L)) → (removed)", "Timber.tag(\"t\").w(\"n=\" + (n + 3L)) → (removed)",
            "slf4j.info(\"n={}\", n + 4L) → (removed)", "println(n + 6L) → (removed)", "logVerbose(n + 7L) → (removed)",
            "return n + 5L → return null",
        ),
    )

    @Test
    fun functionsThatOnlyLookLikeLoggingAreMutated() {
        val mutants = descriptions(
            """
            fun login(attempts: Long): Long = attempts
            fun logarithm(x: Long): Long = x
            fun signIn(attempts: Long): Long = login(attempts + 1L) + logarithm(attempts * 2L)
            """.trimIndent(),
        )
        assertEquals((logicMutants + listOf("attempts + 1L → attempts - 1L", "attempts * 2L → attempts / 2L", "login(attempts + 1L) + logarithm(attempts * 2L) → login(attempts + 1L) - logarithm(attempts * 2L)")).sorted(), mutants)
    }

    @Test
    fun daggerHiltAndMetroModulesAndProviders() = assertArid(
        "dependencyInjection",
        """
        import dagger.Module
        import dagger.Provides

        @Module
        object NetworkModule {
            @Provides fun timeout(base: Long): Long = base * 2L
            fun retries(base: Long): Long = base + 3L
        }

        interface AppGraph {
            @dev.zacsweers.metro.Provides fun cacheSize(base: Long): Long = base - 1L
        }
        """.trimIndent(),
        mapOf(
            "stubs/Dagger.kt" to "package dagger\nannotation class Module\nannotation class Provides",
            "stubs/Metro.kt" to "package dev.zacsweers.metro\nannotation class Provides",
        ),
        mutated = listOf("base * 2L → base / 2L", "base + 3L → base - 3L", "base - 1L → base + 1L").sorted(),
    )

    @Test
    fun koinModuleDsl() = assertArid(
        "dependencyInjection",
        """
        import org.koin.dsl.module

        class Pool(val size: Long)

        fun appModule(base: Long) = module {
            single { Pool(base * 4L) }
        }
        """.trimIndent(),
        mapOf(
            "stubs/Koin.kt" to """
                package org.koin.core.module
                class Module { fun <T> single(definition: () -> T) {} }
            """.trimIndent(),
            "stubs/KoinDsl.kt" to """
                package org.koin.dsl
                fun module(declaration: org.koin.core.module.Module.() -> Unit): org.koin.core.module.Module =
                    org.koin.core.module.Module().apply(declaration)
            """.trimIndent(),
        ),
        mutated = listOf("base * 4L → base / 4L"),
    )

    @Test
    fun toStringOverrides() = assertArid(
        "toString",
        """
        class Money(val cents: Long) {
            override fun toString(): String = "${'$'}" + cents / 100L
        }
        """.trimIndent(),
        mutated = listOf("cents / 100L → cents * 100L"),
    )

    @Test
    fun trivialGettersButNotComputedOnes() {
        val source = """
            class Cart(private var items: Int) {
                private val state = State(items > 0)
                val count: Int get() = items
                val full: Boolean get() = state.flag
                val big: Boolean get() = items > 10
            }

            class State(val flag: Boolean)
        """.trimIndent()
        // Computed getters and property initializers keep their mutants either way.
        val always = logicMutants + listOf("items > 0 → items >= 0", "items > 10 → items >= 10", "return items > 10 → return !(items > 10)")
        assertEquals(always.sorted(), descriptions(source))
        val trivial = listOf("return items → return 0", "return state.flag → return !(state.flag)")
        assertEquals((always + trivial).sorted(), descriptions(source, mutate = setOf("trivialGetters")))
    }

    @Test
    fun cacheLookupsAndTheKeysOfGetOrPut() = assertArid(
        "caches",
        """
        class Prices(private val load: (Int) -> Long) {
            private val cache = HashMap<Int, Long>()
            private val memo = java.util.concurrent.ConcurrentHashMap<Int, Long>()

            fun price(id: Int): Long {
                if (cache[id + 1] != null) return 5L
                if (!cache.containsKey(id)) cache[id] = load(id)
                return cache.getOrPut(id * 2) { load(id) - 1L }
            }

            fun memoized(id: Int): Long = memo.computeIfAbsent(id - 1) { load(it) * 3L }
        }
        """.trimIndent(),
        mutated = listOf(
            "id + 1 → id - 1", "if (!cache.containsKey(id)) → if (true)", "if (!cache.containsKey(id)) → if (false)",
            // Forced true it would read a missing entry through the smart cast; only false, so its negation stays.
            "cache[id + 1] != null → cache[id + 1] == null", "if (cache[id + 1] != null) → if (false)",
            "id * 2 → id / 2", "id - 1 → id + 1",
        ),
        // The value a cache computes is ordinary code.
        always = listOf("load(id) - 1L → load(id) + 1L", "load(it) * 3L → load(it) / 3L"),
    )

    @Test
    fun delaysSleepsAndTimeoutsButNotTheBlockUnderTheTimeout() = assertArid(
        "delays",
        """
        suspend fun poll(attempt: Long, check: () -> Boolean): Boolean {
            kotlinx.coroutines.delay(attempt * 100L)
            Thread.sleep(attempt + 50L)
            return kotlinx.coroutines.withTimeout(attempt * 1000L) { check() && attempt > 2L }
        }
        """.trimIndent(),
        // The block under the timeout is ordinary code; the value the timeout passes on is the block's.
        always = listOf(
            "attempt > 2L → attempt >= 2L",
            "check() && attempt > 2L → check() || attempt > 2L",
            "check() && attempt > 2L → true && attempt > 2L", "check() && attempt > 2L → check() && true",
            "return check() && attempt > 2L → return !(check() && attempt > 2L)",
        ),
        mutated = listOf(
            "attempt * 100L → attempt / 100L", "attempt + 50L → attempt - 50L", "attempt * 1000L → attempt / 1000L",
            "kotlinx.coroutines.delay(attempt * 100L) → (removed)", "Thread.sleep(attempt + 50L) → (removed)",
            "return kotlinx.coroutines.withTimeout(attempt * 1000L) { check() && attempt > 2L } → " +
                "return !(kotlinx.coroutines.withTimeout(attempt * 1000L) { check() && attempt > 2L })",
        ),
    )

    @Test
    fun analyticsAndMetricsCallsButNotIntegerCounters() = assertArid(
        "metrics",
        """
        class Checkout(private val analytics: Analytics, private val metrics: Registry, private val counter: Counter) {
            private var attempts = 0

            fun pay(cents: Long): Long {
                analytics.trackPayment(cents * 100L)
                metrics.record("pay", cents + 1L)
                counter.increment(cents - 1L)
                attempts++
                return cents
            }
        }
        """.trimIndent(),
        extraSources = mapOf(
            "stubs/Metrics.kt" to """
                interface Analytics { fun trackPayment(cents: Long) }
                interface Registry { fun record(name: String, value: Long) }
                interface Counter { fun increment(by: Long) }
            """.trimIndent(),
        ),
        // `attempts++` is an Int, not a metrics counter.
        always = listOf("attempts++ → attempts--"),
        mutated = listOf(
            "cents * 100L → cents / 100L", "cents + 1L → cents - 1L", "cents - 1L → cents + 1L",
            "analytics.trackPayment(cents * 100L) → (removed)", "metrics.record(\"pay\", cents + 1L) → (removed)",
            "counter.increment(cents - 1L) → (removed)",
        ),
    )

    @Test
    fun generatedClassesAndFunctions() = assertArid(
        "generated",
        """
        @javax.annotation.processing.Generated("dagger") class Factory { fun make(x: Long): Long = x * 2L }
        class Mapper { @jakarta.annotation.Generated("mapstruct") fun map(x: Long): Long = x + 3L }
        """.trimIndent(),
        extraSources = mapOf(
            "stubs/Generated.kt" to """
                package javax.annotation.processing
                annotation class Generated(val value: String)
            """.trimIndent(),
            "stubs/JakartaGenerated.kt" to """
                package jakarta.annotation
                annotation class Generated(val value: String)
            """.trimIndent(),
        ),
        mutated = listOf("x * 2L → x / 2L", "x + 3L → x - 3L"),
    )
}
