package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** PRECONDITION_REMOVAL: `require`/`check` are skipped, `requireNotNull`/`checkNotNull` return their value unchecked. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreconditionRemovalTest {
    private lateinit var compiled: Compiled

    private val source =
        """
        fun port(p: Int): Int { require(p in 1..65_535) { "bad port" }; return p }
        fun active(n: Int): Int { check(n < 3); return n }
        fun length(x: String?): Int? { val y = requireNotNull(x) { "missing" }; return y.length }
        fun loose(x: String?): String? = checkNotNull(x)
        @JvmInline value class Percent(val v: Int) { init { require(v in 0..100) } }
        fun percent(v: Int): Int = Percent(v).v
        fun guarded(n: Int): Int { if (n > 10) check(n % 2 == 0); return n }
        """.trimIndent()

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(source, operators = listOf("DEFAULTS", "PRECONDITION_REMOVAL"))
    }

    private fun removal(line: Int): ManifestEntry = compiled.mutantsOf("PRECONDITION_REMOVAL").single { it.line == line }

    @Test
    fun requireAndCheckAreSkipped() {
        assertThrows(IllegalArgumentException::class.java) { compiled.call("port", 0) }
        assertEquals(0, compiled.call("port", 0, activeId = removal(1).id))
        assertEquals("require(p in 1..65_535) { \"bad port\" } → (removed)", removal(1).description)
        assertThrows(IllegalStateException::class.java) { compiled.call("active", 3) }
        assertEquals(3, compiled.call("active", 3, activeId = removal(2).id))
        assertEquals(11, compiled.call("guarded", 11, activeId = removal(7).id))
    }

    @Test
    fun notNullChecksReturnTheValueUnchecked() {
        assertEquals("requireNotNull(x) { \"missing\" } → x", removal(3).description)
        assertEquals(2, compiled.call("length", "ab", activeId = removal(3).id))
        assertThrows(IllegalArgumentException::class.java) { compiled.call("length", null) }
        assertThrows(NullPointerException::class.java) { compiled.call("length", null, activeId = removal(3).id) }
        assertThrows(IllegalStateException::class.java) { compiled.call("loose", null) }
        assertNull(compiled.call("loose", null, activeId = removal(4).id))
    }

    @Test
    fun valueClassInitBlocksToo() {
        assertThrows(IllegalArgumentException::class.java) { compiled.call("percent", 101) }
        assertEquals(101, compiled.call("percent", 101, activeId = removal(5).id))
    }

    @Test
    fun removeCallNoLongerTakesRequireStatementsWhileThisIsOn() {
        assertEquals(emptyList<ManifestEntry>(), compiled.mutantsOf("REMOVE_CALL"))
        val defaults = Harness.compile(source)
        assertEquals(listOf(2, 5, 7), defaults.mutantsOf("REMOVE_CALL").map { it.line })
        assertEquals(emptyList<ManifestEntry>(), defaults.mutantsOf("PRECONDITION_REMOVAL"))
    }
}
