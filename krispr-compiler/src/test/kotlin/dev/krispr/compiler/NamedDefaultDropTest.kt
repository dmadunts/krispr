package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** NAMED_DEFAULT_DROP: `f(n = 3) → f()` for a named argument whose parameter has a default. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamedDefaultDropTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun f(sep: String = ",", n: Int = 2): String = sep + n
            fun named(): String = f(n = 3)
            fun positional(): String = f(";", 3)
            fun same(): String = f(sep = ",", n = 5)
            fun joined(xs: List<Int>): String = xs.joinToString(separator = ";")
            class Box(val w: Int = 1, val h: Int = 1) { fun area() = w * h }
            fun box(): Int = Box(w = 3, h = 4).area()
            interface Shape { fun scaled(k: Int = 2): Int }
            class Square : Shape { override fun scaled(k: Int): Int = k * 10 }
            fun square(): Int = Square().scaled(k = 5)
            fun next(calls: IntArray): Int { calls[0]++; return calls[0] }
            fun ordered(calls: IntArray): String = f(n = next(calls), sep = "s" + calls[0]) + calls[0]
            data class D(val a: Int = 0)
            fun copied(d: D): D = d.copy(a = 1)
            fun required(a: Int, b: Int = 0): Int = a - b
            fun needed(): Int = required(a = 5, b = 1)
            """.trimIndent(),
            operators = listOf("NAMED_DEFAULT_DROP"),
        )
    }

    private fun drops(line: Int): List<ManifestEntry> = compiled.mutantsOf("NAMED_DEFAULT_DROP").filter { it.line == line }

    private fun dropping(line: Int, text: String): ManifestEntry = drops(line).single { it.description.endsWith(" → $text") }

    @Test
    fun aNamedArgumentFallsBackToItsDefault() {
        assertEquals(listOf("f(n = 3) → f()"), drops(2).map { it.description })
        assertEquals(",3", compiled.call("named"))
        assertEquals(",2", compiled.call("named", activeId = drops(2).single().id))
    }

    @Test
    fun positionalArgumentsAndTheDefaultItselfAreLeftAlone() {
        assertEquals(emptyList<ManifestEntry>(), drops(3))
        assertEquals(listOf("f(sep = \",\", n = 5) → f(sep = \",\")"), drops(4).map { it.description })
    }

    @Test
    fun libraryDefaultsCount() {
        val id = dropping(5, "xs.joinToString()").id
        assertEquals("1;2", compiled.call("joined", listOf(1, 2)))
        assertEquals("1, 2", compiled.call("joined", listOf(1, 2), activeId = id))
    }

    @Test
    fun constructorsAndOverridesToo() {
        assertEquals(12, compiled.call("box"))
        assertEquals(4, compiled.call("box", activeId = dropping(7, "Box(h = 4)").id))
        assertEquals(3, compiled.call("box", activeId = dropping(7, "Box(w = 3)").id))
        assertEquals(20, compiled.call("square", activeId = dropping(10, "Square().scaled()").id))
    }

    @Test
    fun argumentsAreEvaluatedOnceInOrderEitherWay() {
        assertEquals("s111", compiled.call("ordered", IntArray(1)))
        assertEquals("s121", compiled.call("ordered", IntArray(1), activeId = dropping(12, "f(sep = \"s\" + calls[0])").id))
        assertEquals(",11", compiled.call("ordered", IntArray(1), activeId = dropping(12, "f(n = next(calls))").id))
    }

    @Test
    fun copiesAndParametersWithoutDefaultsAreLeftAlone() {
        assertEquals(emptyList<ManifestEntry>(), drops(14))
        assertEquals(listOf("required(a = 5, b = 1) → required(a = 5)"), drops(16).map { it.description })
    }
}
