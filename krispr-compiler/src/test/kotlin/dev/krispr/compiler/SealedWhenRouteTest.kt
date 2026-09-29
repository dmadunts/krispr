package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** SEALED_WHEN_ROUTE: a branch of a `when` over a sealed type runs another branch's body. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SealedWhenRouteTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            sealed interface E { data class A(val n: Int) : E; data object B : E; class C(val s: String) : E; data object D : E }
            fun e(k: Int): E = when (k) { 0 -> E.A(7); 1 -> E.B; 2 -> E.C("abc"); else -> E.D }
            fun value(k: Int): Int = when (val x = e(k)) { is E.A -> x.n; E.B -> 2; is E.C -> x.s.length; E.D -> 4 }
            fun statement(k: Int): String { val out = mutableListOf<String>(); when (e(k)) { is E.A -> out.add("a"); E.B -> out.add("b"); is E.C -> out.add("c"); E.D -> Unit }; return out.toString() }
            fun same(k: Int): Int = when (e(k)) { is E.A -> 1; E.B -> 1; is E.C -> 2; E.D -> 3 }
            sealed class Res { class Ok(val v: Int) : Res(); object Fail : Res() }
            fun res(ok: Boolean): String = when (val r = if (ok) Res.Ok(1) else Res.Fail) { is Res.Ok -> "ok " + r.v; Res.Fail -> "fail" }
            fun open(x: Any): Int = when (x) { is String -> 1; is Int -> 2; else -> 3 }
            fun withElse(k: Int): Int = when (e(k)) { is E.A -> 1; E.B -> 2; else -> 3 }
            """.trimIndent(),
            operators = listOf("SEALED_WHEN_ROUTE"),
        )
    }

    private fun routes(line: Int): List<ManifestEntry> = compiled.mutantsOf("SEALED_WHEN_ROUTE").filter { it.line == line }

    private fun route(line: Int, from: String): ManifestEntry = routes(line).single { it.description.startsWith("$from → ") }

    @Test
    fun eachBranchRunsTheNextBodyThatNeedsNoCast() {
        assertEquals(
            listOf("E.B → runs the E.D branch", "E.D → runs the E.B branch", "is E.A → runs the E.B branch", "is E.C → runs the E.D branch"),
            routes(3).map { it.description }.sorted(),
        )
        assertEquals(listOf(7, 2, 3, 4), (0..3).map { compiled.call("value", it) })
        assertEquals(2, compiled.call("value", 0, activeId = route(3, "is E.A").id))
        assertEquals(7, compiled.call("value", 0, activeId = route(3, "E.B").id))
        assertEquals(4, compiled.call("value", 1, activeId = route(3, "E.B").id))
        assertEquals(2, compiled.call("value", 3, activeId = route(3, "E.D").id))
    }

    @Test
    fun statementsWithBodiesOfDifferentTypes() {
        assertEquals("[b]", compiled.call("statement", 0, activeId = route(4, "is E.A").id))
        assertEquals("[a]", compiled.call("statement", 3, activeId = route(4, "E.D").id))
        assertEquals("[c]", compiled.call("statement", 2))
    }

    @Test
    fun aBodyTheSameAsTheBranchOwnIsSkipped() {
        assertEquals("is E.A → runs the is E.C branch", route(5, "is E.A").description)
        assertEquals(2, compiled.call("same", 0, activeId = route(5, "is E.A").id))
    }

    @Test
    fun sealedClassesTooButNotOpenTypes() {
        assertEquals(listOf("is Res.Ok → runs the Res.Fail branch"), routes(7).map { it.description })
        assertEquals("fail", compiled.call("res", true, activeId = routes(7).single().id))
        assertEquals(emptyList<ManifestEntry>(), routes(8))
        assertEquals(2, routes(9).size)
    }
}
