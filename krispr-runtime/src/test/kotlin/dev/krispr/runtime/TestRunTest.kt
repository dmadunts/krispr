package dev.krispr.runtime

import io.kotest.core.spec.Spec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.ClassNameFilter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths

class TestRunTest {
    @Test
    fun batchesGrowGeometricallySoTheCheapestTestRunsAlone() {
        val selectors = (1..10).map { "t$it" }
        assertEquals(listOf(1, 1, 2, 4, 2), TestRun.batches(selectors).map { it.size })
        assertEquals(selectors, TestRun.batches(selectors).flatten())
    }

    @Test
    fun noSelectorsNoBatches() {
        assertEquals(emptyList<List<String>>(), TestRun.batches(emptyList()))
    }

    @Test
    fun causeChainNamesEachCauseAndWhereTheRootOneWasThrown() {
        val root = IllegalAccessException("module java.base does not open java.io").apply {
            stackTrace = Array(10) { StackTraceElement("demo.Frames", "frame$it", "Frames.kt", it + 1) }
        }
        val error = RuntimeException("Can't instantiate proxy", IllegalStateException("proxy maker failed", root))
        assertEquals(
            listOf(
                "caused by: java.lang.IllegalStateException: proxy maker failed",
                "caused by: java.lang.IllegalAccessException: module java.base does not open java.io",
                "  at demo.Frames.frame0(Frames.kt:1)",
                "  at demo.Frames.frame1(Frames.kt:2)",
                "  at demo.Frames.frame2(Frames.kt:3)",
                "  ... 7 more",
            ),
            TestRun.causeChain(error, frames = 3),
        )
    }

    @Test
    fun causeChainOfALoneExceptionIsWhereItWasThrown() {
        val error = AssertionError("expected 2").apply { stackTrace = arrayOf(StackTraceElement("demo.CartTest", "total", "CartTest.kt", 9)) }
        assertEquals(listOf("  at demo.CartTest.total(CartTest.kt:9)"), TestRun.causeChain(error))
    }

    @Test
    fun causeChainStopsAtACycle() {
        val first = RuntimeException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertEquals(listOf("caused by: java.lang.IllegalStateException: second"), TestRun.causeChain(first, frames = 0).filter { !it.startsWith(" ") })
    }

    @Test
    fun printedFailuresCarryTheirCauses() {
        val failure = TestFailure("[engine:x]/[test:t]", "t", "java.lang.RuntimeException: wrapped", causes = listOf("caused by: java.io.IOException: root", "  at a.B.c(B.kt:1)"))
        val bytes = ByteArrayOutputStream()
        TestRunResult(1, listOf(failure)).printFailures(PrintStream(bytes, true))
        assertEquals(
            "FAILED t: java.lang.RuntimeException: wrapped\n  caused by: java.io.IOException: root\n    at a.B.c(B.kt:1)\n",
            bytes.toString().replace(System.lineSeparator(), "\n"),
        )
    }
}

class KotestSpecsTest {
    private val loader = javaClass.classLoader
    private val roots = setOf(Paths.get(Spec::class.java.protectionDomain.codeSource.location.toURI()))

    private fun specs(vararg filters: Filter<*>) =
        TestRun.kotestSpecs(roots, filters.toList(), loader).filter { it.startsWith("dev.krispr.fixture.kotest.") }

    @Test
    fun selectsConcreteSpecsIncludingNestedOnes() {
        assertEquals(
            listOf("dev.krispr.fixture.kotest.CalculatorSpec", "dev.krispr.fixture.kotest.Outer\$NestedSpec", "dev.krispr.fixture.kotest.SlowSpec"),
            specs(),
        )
    }

    @Test
    fun appliesTheTestTaskClassNameFilters() {
        assertEquals(listOf("dev.krispr.fixture.kotest.CalculatorSpec"), specs(ClassNameFilter.excludeClassNamePatterns(".*Slow.*", ".*Nested.*")))
        assertEquals(listOf("dev.krispr.fixture.kotest.SlowSpec"), specs(ClassNameFilter.includeClassNamePatterns(".*SlowSpec")))
    }

    @Test
    fun nothingWithoutKotest() {
        assertEquals(emptyList<String>(), TestRun.kotestSpecs(roots, emptyList(), loader, specClass = "io.kotest.missing.Spec"))
    }
}
