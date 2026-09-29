package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** EMPTY_STRING_RETURNS: a named function's String return becomes `""`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmptyStringReturnsTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun trimmed(s: String): String = s.trim()
            fun empty(): String = ""
            class Song(val title: String) { override fun toString(): String = "Song(" + title + ")"; val label: String get() = title.uppercase() }
            fun number(n: Int): String? = if (n > 0) n.toString() else null
            fun known(): String? = "k"
            fun absent(flag: Boolean): String? { if (flag) return "set"; return null }
            fun shout(s: String): String = listOf(s).map { it + "!" }.single()
            fun tag(calls: IntArray): String = next(calls) + "!"
            fun next(calls: IntArray): String { calls[0]++; return "t" + calls[0] }
            fun label(s: String): String = Song(s).label
            fun name(s: String): String = Song(s).toString()
            fun counted(calls: IntArray): Int { tag(calls); return calls[0] }
            """.trimIndent(),
            operators = listOf("DEFAULTS", "EMPTY_RETURNS", "EMPTY_STRING_RETURNS"),
        )
    }

    private fun empties(line: Int): List<ManifestEntry> = compiled.mutantsOf("EMPTY_STRING_RETURNS").filter { it.line == line }

    @Test
    fun aStringReturnBecomesEmpty() {
        val mutant = empties(1).single()
        assertEquals("return s.trim() → return \"\"", mutant.description)
        assertEquals("a", compiled.call("trimmed", " a "))
        assertEquals("", compiled.call("trimmed", " a ", activeId = mutant.id))
        // EMPTY_RETURNS leaves named functions to it.
        assertEquals(emptyList<ManifestEntry>(), compiled.mutantsOf("EMPTY_RETURNS").filter { it.line == 1 })
    }

    @Test
    fun aCustomGetterCountsButToStringAndLiteralEmptiesDoNot() {
        assertEquals(listOf("return title.uppercase() → return \"\""), empties(3).map { it.description })
        assertEquals("", compiled.call("label", "a", activeId = empties(3).single().id))
        assertEquals("Song(a)", compiled.call("name", "a"))
        assertEquals(emptyList<ManifestEntry>(), empties(2))
    }

    @Test
    fun aNullableReturnThatMayBeNullBecomesEmptyToo() {
        val id = empties(4).single().id
        assertEquals(null, compiled.call("number", 0))
        assertEquals("", compiled.call("number", 0, activeId = id))
        assertEquals("", compiled.call("number", 3, activeId = id))
        assertEquals(1, compiled.mutantsOf("NULL_RETURNS").count { it.line == 4 })
        // A String? function returning a non-null String only gets NULL_RETURNS' mutant.
        assertEquals(emptyList<ManifestEntry>(), empties(5))
        assertEquals(listOf("return null → return \"\""), empties(6).map { it.description })
        assertEquals("", compiled.call("absent", false, activeId = empties(6).single().id))
        assertEquals("set", compiled.call("absent", true, activeId = empties(6).single().id))
    }

    @Test
    fun lambdasAreLeftToEmptyReturns() {
        assertEquals(1, empties(7).size)
        assertEquals(1, compiled.mutantsOf("EMPTY_RETURNS").count { it.line == 7 })
    }

    @Test
    fun theValueIsStillEvaluatedOnce() {
        val id = empties(8).single().id
        assertEquals(1, compiled.call("counted", IntArray(1), activeId = id))
        assertEquals(1, compiled.call("counted", IntArray(1)))
    }
}
