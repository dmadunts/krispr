package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** SAFE_CALL_BODY: `x?.let { … }` whose value nothing reads is skipped, as if `x` were null. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SafeCallBodyTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun tags(title: String?, artist: String?): Map<String, String> { val m = mutableMapOf<String, String>(); title?.let { m.put("T", it.trim()) }; artist?.also { m["A"] = it }; return m }
            fun built(title: String?): Map<String, String> = buildMap { title?.let { put("T", it) } }
            fun applied(sb: StringBuilder?): String { sb?.apply { append("x"); append("y") }; return sb.toString() }
            fun ran(xs: MutableList<String>?): Int { xs?.run { add("a"); add("b") }; return xs?.size ?: -1 }
            fun cleared(xs: MutableList<String>?): Int { xs?.clear(); return xs?.size ?: -1 }
            fun read(x: String?): Int? = x?.let { it.length + 1 }
            fun bound(x: String?): Int { val n = x?.let { it.length }; return n ?: 0 }
            fun single(x: String?, sink: MutableList<String>) { x?.let { sink.add(it) } }
            fun unit(x: String?, sink: (String) -> Unit) { x?.let { sink(it) } }
            fun counted(calls: IntArray, m: MutableMap<String, Int>): Int { next(calls)?.let { m["n"] = it; m["m"] = it }; return calls[0] }
            fun next(calls: IntArray): Int? { calls[0]++; return calls[0] }
            fun each(xs: List<String?>, m: MutableMap<String, Int>): Int { xs.forEach { x -> x?.let { m[it] = it.length } }; return m.size }
            fun chained(n: Int?, m: MutableMap<String, Int>): Int { n?.takeIf { it > 0 }?.let { m["n"] = it }; return m.size }
            """.trimIndent(),
            operators = listOf("DEFAULTS", "SAFE_CALL_BODY"),
        )
    }

    private fun skip(line: Int): List<ManifestEntry> = compiled.mutantsOf("SAFE_CALL_BODY").filter { it.line == line }

    @Test
    fun discardedLetAndAlsoBodiesAreSkipped() {
        val (let, also) = skip(1).sortedBy { it.description }
        assertEquals("artist?.also { m[\"A\"] = it } → (skipped)", let.description)
        assertEquals("title?.let { m.put(\"T\", it.trim()) } → (skipped)", also.description)
        assertEquals(mapOf("T" to "t", "A" to "a"), compiled.call("tags", " t ", "a"))
        assertEquals(mapOf("A" to "a"), compiled.call("tags", " t ", "a", activeId = also.id))
        assertEquals(mapOf("T" to "t"), compiled.call("tags", " t ", "a", activeId = let.id))
        // A null receiver never reaches the switch.
        assertEquals(emptyMap<String, String>(), compiled.call("tags", null, null, activeId = also.id))
        assertEquals(emptyMap<String, String>(), compiled.call("built", "t", activeId = skip(2).single().id))
    }

    @Test
    fun applyRunAndPlainUnitCallsAreSkipped() {
        assertEquals("xy", compiled.call("applied", StringBuilder()))
        assertEquals("", compiled.call("applied", StringBuilder(), activeId = skip(3).single().id))
        assertEquals(2, compiled.call("ran", mutableListOf<String>()))
        assertEquals(0, compiled.call("ran", mutableListOf<String>(), activeId = skip(4).single().id))
        assertEquals(0, compiled.call("cleared", mutableListOf("a")))
        assertEquals(1, compiled.call("cleared", mutableListOf("a"), activeId = skip(5).single().id))
    }

    @Test
    fun aValueSomethingReadsIsLeftAlone() {
        assertEquals(emptyList<ManifestEntry>(), skip(6) + skip(7))
        assertEquals(2, compiled.call("read", "a"))
        assertEquals(1, compiled.call("bound", "a"))
    }

    @Test
    fun aBodyOfOneRemovableCallIsLeftToRemoveCall() {
        // `sink.add` returns Boolean, so nothing else skips it; `sink(it)` is REMOVE_CALL's.
        assertEquals(1, skip(8).size)
        assertEquals(emptyList<ManifestEntry>(), skip(9))
        assertEquals(1, compiled.mutantsOf("REMOVE_CALL").count { it.line == 9 })
    }

    @Test
    fun theReceiverIsEvaluatedOnceEitherWay() {
        val id = skip(10).single().id
        val on = mutableMapOf<String, Int>()
        assertEquals(1, compiled.call("counted", IntArray(1), on, activeId = id))
        assertEquals(emptyMap<String, Int>(), on)
        val off = mutableMapOf<String, Int>()
        assertEquals(1, compiled.call("counted", IntArray(1), off))
        assertEquals(mapOf("n" to 1, "m" to 1), off)
    }

    @Test
    fun lambdaStatementsAndChainsCount() {
        assertEquals(2, compiled.call("each", listOf("a", null, "bc"), mutableMapOf<String, Int>()))
        assertEquals(0, compiled.call("each", listOf("a", null, "bc"), mutableMapOf<String, Int>(), activeId = skip(12).single().id))
        // Only the outer `?.let`, whose value is discarded; the `?.takeIf` feeds it.
        val id = skip(13).single().id
        assertEquals(1, compiled.call("chained", 3, mutableMapOf<String, Int>()))
        assertEquals(0, compiled.call("chained", 3, mutableMapOf<String, Int>(), activeId = id))
        assertEquals(0, compiled.call("chained", 0, mutableMapOf<String, Int>()))
    }
}
