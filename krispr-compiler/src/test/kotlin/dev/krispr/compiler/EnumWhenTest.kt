package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A `when` over an enum keeps the JVM backend's switch on the ordinal. An instance that is none of the entries,
 * as a mocking library's relaxed enum value is, takes the branch of the entry with its ordinal uninstrumented; it
 * must do the same with no mutant active rather than throw `NoWhenBranchMatchedException` (#35).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnumWhenTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            enum class Suit { Hearts, Spades, Clubs }
            fun Suit.label(): String = when (this) { Suit.Hearts -> "hearts"; Suit.Spades -> "spades"; Suit.Clubs -> "clubs" }
            fun grouped(s: Suit): Int = when (s) { Suit.Hearts, Suit.Spades -> 1; Suit.Clubs -> 2 }
            fun chosen(s: Suit): Int = when (val x = s) { Suit.Hearts -> 1; else -> x.ordinal * 10 }
            fun plain(s: Suit): Int = when { s == Suit.Hearts -> 1; else -> 2 }
            """.trimIndent(),
        )
    }

    /** An instance of `Suit` made without its constructor, as Objenesis makes a mock: ordinal 0, identical to no entry. */
    private fun mockSuit(): Any {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, compiled.classLoader.loadClass("Suit"))
    }

    @Test
    fun anInstanceThatIsNoEntryTakesItsOrdinalsBranch() {
        assertEquals("hearts", compiled.call("label", mockSuit()))
        assertEquals(1, compiled.call("grouped", mockSuit()))
        assertEquals(1, compiled.call("chosen", mockSuit()))
        assertTrue(compiled.result.outputDirectory.walk().any { it.name == "SampleKt\$WhenMappings.class" })
    }

    @Test
    fun entryConditionsGetNoMutantsButBodiesDo() {
        val conditions = listOf("NEGATE_EQUALITY", "BOOLEAN_LOGIC", "CONDITION_TRUE", "CONDITION_FALSE")
        assertEquals(emptyList<ManifestEntry>(), compiled.mutants.filter { it.line in 2..4 && it.operator in conditions })
        val times = compiled.mutantsOf("MATH").single { it.line == 4 }
        val clubs = compiled.classLoader.loadClass("Suit").enumConstants[2]
        assertEquals(20, compiled.call("chosen", clubs))
        assertEquals(0, compiled.call("chosen", clubs, activeId = times.id))
        // Without a subject the backend compares by identity anyway, so the condition keeps its mutants.
        assertEquals(listOf("CONDITION_FALSE", "CONDITION_TRUE"), compiled.mutants.filter { it.line == 5 && it.operator in conditions }.map { it.operator }.sorted())
    }
}
