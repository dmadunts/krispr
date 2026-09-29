package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** REMOVE_ASSIGNMENT: a store to a member `var` or a state holder's `value` is skipped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoveAssignmentTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            import kotlinx.coroutines.flow.MutableStateFlow
            class Player(var volume: Int = 5) {
                var muted = false
                    private set
                private var hidden = 0
                private var readBack = 0
                private var cache: String? = null
                lateinit var name: String
                val state = MutableStateFlow(0)
                init { muted = volume == 0 }
                fun mute() { muted = true; hidden = 1; cache = "x" }
                fun remember(n: Int) { readBack = n }
                fun recall() = readBack
                fun publish(n: Int) { state.value = n }
                fun rename(n: String) { name = n }
                fun clamp(n: Int) { if (n > 10) volume = 10 }
            }
            fun louder(p: Player, calls: IntArray): Int { p.volume = next(calls); return calls[0] }
            fun next(calls: IntArray): Int { calls[0]++; return calls[0] * 10 }
            fun mute(p: Player): Boolean { p.mute(); return p.muted }
            fun recall(p: Player): Int { p.remember(4); return p.recall() }
            fun publish(p: Player): Int { p.publish(3); return p.state.value }
            fun clamp(p: Player): Int { p.clamp(20); return p.volume }
            fun local(): Int { var x = 1; x = 2; return x }
            """.trimIndent(),
            operators = listOf("DEFAULTS", "REMOVE_ASSIGNMENT"),
        )
    }

    private fun removed(line: Int): List<ManifestEntry> = compiled.mutantsOf("REMOVE_ASSIGNMENT").filter { it.line == line }

    private fun player() = compiled.classLoader.loadClass("Player").getConstructor(Int::class.java).newInstance(5)

    @Test
    fun aStoreToAReadPropertyIsRemoved() {
        val id = removed(11).single().id
        assertEquals("muted = true → (removed)", removed(11).single().description)
        assertEquals(true, compiled.call("mute", player()))
        assertEquals(false, compiled.call("mute", player(), activeId = id))
        assertEquals(4, compiled.call("recall", player()))
        assertEquals(0, compiled.call("recall", player(), activeId = removed(12).single().id))
    }

    @Test
    fun stateHolderValuesAreRemoved() {
        val id = removed(14).single().id
        assertEquals("state.value = n → (removed)", removed(14).single().description)
        assertEquals(3, compiled.call("publish", player()))
        assertEquals(0, compiled.call("publish", player(), activeId = id))
    }

    @Test
    fun theReceiverAndValueAreEvaluatedOnceEitherWay() {
        val id = removed(18).single().id
        val p = player()
        assertEquals(1, compiled.call("louder", p, IntArray(1), activeId = id))
        assertEquals(1, compiled.call("louder", p, IntArray(1)))
        assertEquals(5, compiled.call("clamp", player(), activeId = removed(16).single().id))
        assertEquals(10, compiled.call("clamp", player()))
    }

    @Test
    fun initializationUnreadCachesLateinitAndLocalsAreLeftAlone() {
        // Line 11 has `muted` only: `hidden` is never read, `cache` is a cache.
        assertEquals(emptyList<ManifestEntry>(), removed(10) + removed(15) + removed(24))
        assertEquals(listOf("muted = true → (removed)"), removed(11).map { it.description })
    }

    @Test
    fun aConditionalStoreIsNotAlsoForcedFalse() {
        // `if (n > 10) volume = 10`: CONDITION_FALSE would repeat the removal.
        assertEquals(listOf("CONDITION_TRUE"), compiled.mutants.filter { it.line == 16 && it.operator.startsWith("CONDITION_") }.map { it.operator })
    }
}
