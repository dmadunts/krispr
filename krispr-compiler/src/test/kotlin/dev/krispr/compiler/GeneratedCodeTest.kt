package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** (c) compiler-generated code gets no mutants; user code inside suspend functions still does. */
class GeneratedCodeTest {
    @Test
    fun dataClassMembersAndObjectOverridesAreSkipped() {
        val compiled = Harness.compile(
            """
            data class Point(val x: Int, val y: Int, val label: String?)

            class Box(val v: Int) {
                override fun toString(): String = "Box(" + (v + 1) + ")"
                override fun hashCode(): Int = v * 31
                override fun equals(other: Any?): Boolean = other is Box && other.v == v
            }

            fun describe(p: Point): Int? = p.label?.length

            fun logged(x: Int) {
                println(x + 1)
                logDebug(x * 2 > 3)
            }

            fun logDebug(flag: Boolean) = Unit
            """.trimIndent(),
        )
        // `?.` desugars to a null check that gets no mutant; the nullable value it returns does.
        assertEquals(listOf("return p.label?.length → return null"), compiled.mutants.map { it.description })
    }

    @Test
    fun suspendFunctionsProduceOnlyUserSiteMutants() {
        val compiled = Harness.compile(
            """
            import kotlin.coroutines.*

            suspend fun fetch(value: Long): Long = suspendCoroutine { it.resume(value) }

            suspend fun total(a: Long, b: Long): Long {
                val x = fetch(a)
                val y = fetch(b)
                return x + y
            }

            fun runTotal(a: Long, b: Long): Long {
                var result = 0L
                suspend { total(a, b) }.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
                return result
            }
            """.trimIndent(),
        )
        // Removing a user-written Unit call is a user-site mutant too.
        assertEquals(setOf("MATH", "REMOVE_CALL"), compiled.mutants.map { it.operator }.toSet(), compiled.mutants.toString())
        val mutant = compiled.mutantsOf("MATH").single()
        assertEquals(8, mutant.line)
        assertEquals(10L, compiled.call("runTotal", 7L, 3L))
        assertEquals(4L, compiled.call("runTotal", 7L, 3L, activeId = mutant.id))
    }

    @Test
    fun enumsValueClassesLazyAndConstantsProduceNoJunk() {
        val compiled = Harness.compile(
            """
            enum class Color(val rgb: Long) { RED(0xFF0000L), GREEN(0x00FF00L) }
            @JvmInline value class Meters(val value: Long)
            const val LIMIT: Long = 2L * 3L
            val cached: String by lazy { "x" }
            class Holder { val name: String by lazy { "y" } }
            object Registry { val names = listOf("a", "b") }
            fun colorCount(): Int = Color.entries.size
            """.trimIndent(),
        )
        // RETURN_VALUE on the user's own Int function is the only mutant.
        assertEquals(listOf("RETURN_VALUE"), compiled.mutants.map { it.operator })
    }

    @Test
    fun jvmOverloadsBridgesCarryNoCopiesOfTheDefaultValue() {
        val compiled = Harness.compile(
            """
            @JvmOverloads fun scaled(x: Long, factor: Long = x * 2L): Long = factor
            fun viaOverload(x: Long): Long = scaled(x)
            """.trimIndent(),
        )
        val mutant = compiled.mutants.single()
        assertEquals("x * 2L → x / 2L", mutant.description)
        assertEquals(10L, compiled.call("viaOverload", 5L))
        assertEquals(2L, compiled.call("viaOverload", 5L, activeId = mutant.id))
        // The generated one-argument bridge reaches the same mutant rather than a copy of it.
        val bridge = compiled.classLoader.loadClass("SampleKt").getMethod("scaled", Long::class.javaPrimitiveType)
        val previous = dev.krispr.runtime.Mutants.activeId
        dev.krispr.runtime.Mutants.activeId = mutant.id
        try {
            assertEquals(2L, bridge.invoke(null, 5L))
        } finally {
            dev.krispr.runtime.Mutants.activeId = previous
        }
    }

    @Test
    fun lambdasAndInlineFunctionsAreMutatedOnceAtTheUserSite() {
        val compiled = Harness.compile(
            """
            inline fun twice(block: (Long) -> Long): Long = block(1L) + block(2L)
            fun sumPlusOne(xs: List<Long>): Long = xs.sumOf { it + 1L }
            fun useTwice(): Long = twice { it * 10L }
            """.trimIndent(),
        )
        val descriptions = compiled.mutants.map { it.description }.sorted()
        assertEquals(listOf("block(1L) + block(2L) → block(1L) - block(2L)", "it * 10L → it / 10L", "it + 1L → it - 1L"), descriptions)
        assertEquals(compiled.mutants.size, compiled.mutants.map { it.id }.toSet().size)
        val lambda = compiled.mutants.single { it.description.startsWith("it + 1L") }
        assertEquals("sumPlusOne(kotlin.collections.List)", lambda.declaration)
        assertEquals(5L, compiled.call("sumPlusOne", listOf(1L, 2L)))
        assertEquals(1L, compiled.call("sumPlusOne", listOf(1L, 2L), activeId = lambda.id))
        val inlineBody = compiled.mutants.single { it.description.startsWith("block(1L)") }
        assertEquals(30L, compiled.call("useTwice"))
        assertEquals(-10L, compiled.call("useTwice", activeId = inlineBody.id))
    }
}
