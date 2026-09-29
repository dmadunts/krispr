package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** ARGUMENT_PROPAGATION: a same-type standard library transform is replaced by its receiver. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArgumentPropagationTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun version(tag: String): String = tag.removePrefix("v")
            fun slug(title: String): String = title.replace(" ", "-")
            fun host(url: String): String = url.substringAfter("://").substringBefore("/")
            fun label(name: String): String = name.ifBlank { "untitled" }
            fun display(name: String): String? = name.takeIf { it.isNotBlank() }
            fun withExtra(xs: List<Int>, x: Int): List<Int> = xs + x
            fun without(xs: Set<Int>, x: Int): Set<Int> = xs - x
            fun whole(x: Double): Double = kotlin.math.floor(x)
            fun safe(tag: String?): String? = tag?.removeSuffix("!")
            fun count(tag: String): Int = tag.removePrefix("v").length
            fun sum(a: Int, b: Int): Int = a + b
            fun text(a: String, b: String): String = a + b
            """.trimIndent(),
            operators = listOf("DEFAULTS"),
        )
    }

    private fun only(line: Int): ManifestEntry =
        compiled.mutants.singleOrNull { it.operator == "ARGUMENT_PROPAGATION" && it.line == line }
            ?: error("no single ARGUMENT_PROPAGATION on line $line: ${compiled.mutants.filter { it.line == line }}")

    private fun assertMutates(function: String, line: Int, args: List<Any?>, original: Any?, mutated: Any?) {
        assertEquals(original, compiled.call(function, *args.toTypedArray()), "$function original")
        assertEquals(mutated, compiled.call(function, *args.toTypedArray(), activeId = only(line).id), "$function mutated")
    }

    @Test
    fun textTransformsAreSkipped() {
        assertMutates("version", 1, listOf("v1.2"), "1.2", "v1.2")
        assertEquals("tag.removePrefix(\"v\") → tag", only(1).description)
        assertMutates("slug", 2, listOf("a b"), "a-b", "a b")
        assertEquals(2, compiled.mutants.count { it.operator == "ARGUMENT_PROPAGATION" && it.line == 3 })
        assertMutates("label", 4, listOf(" "), "untitled", " ")
        assertMutates("safe", 9, listOf("x!"), "x", "x!")
        assertMutates("count", 10, listOf("v12"), 2, 3)
    }

    @Test
    fun takeIfStandsForItsNullableResult() {
        assertMutates("display", 5, listOf(" "), null, " ")
    }

    @Test
    fun collectionPlusAndMinusAndRounding() {
        assertMutates("withExtra", 6, listOf(listOf(1), 2), listOf(1, 2), listOf(1))
        assertEquals("xs + x → xs", only(6).description)
        assertMutates("without", 7, listOf(setOf(1, 2), 2), setOf(1), setOf(1, 2))
        assertMutates("whole", 8, listOf(1.5), 1.0, 1.5)
    }

    @Test
    fun arithmeticAndStringConcatenationAreNotPropagated() {
        assertTrue(compiled.mutants.none { it.operator == "ARGUMENT_PROPAGATION" && it.line in 11..12 })
        assertEquals(1, compiled.mutants.count { it.operator == "MATH" && it.line == 11 })
    }

    @Test
    fun canBeTurnedOff() {
        val source = "fun version(tag: String): String = tag.removePrefix(\"v\")"
        assertEquals(1, Harness.compile(source).mutantsOf("ARGUMENT_PROPAGATION").size)
        assertTrue(Harness.compile(source, operators = listOf("REMOVE_CHAIN_CALL")).mutants.isEmpty())
    }
}
