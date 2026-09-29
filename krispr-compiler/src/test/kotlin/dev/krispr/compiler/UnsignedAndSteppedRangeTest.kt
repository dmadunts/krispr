package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** RANGE_BOUNDARY on UInt/ULong ranges and on `step` progressions, and BITWISE on UInt/ULong. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnsignedAndSteppedRangeTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun uinside(x: Int, lo: Int, hi: Int): Boolean = x.toUInt() in lo.toUInt()..hi.toUInt()
            fun ubelow(x: Long): Boolean = x.toULong() in 0uL until 10uL
            fun stepped(x: Int): Boolean = x in 1..9 step 2
            fun steppedDown(x: Int): Boolean = x !in 10 downTo 0 step 5
            fun uand(a: Int, b: Int): Int = (a.toUInt() and b.toUInt()).toInt()
            fun ushl(a: Long): Long = (a.toULong() shl 2).toLong()
            fun uinv(a: Int): Int = a.toUInt().inv().toInt()
            fun next(calls: IntArray): Int { calls[0]++; return calls[0] }
            fun counted(calls: IntArray): Int { val inside = next(calls) in next(calls)..next(calls) step next(calls); return calls[0] * 10 + (if (inside) 1 else 0) }
            """.trimIndent(),
        )
    }

    private fun mutants(operator: String, line: Int) = compiled.mutants.filter { it.operator == operator && it.line == line }.sortedBy { it.description }

    private fun assertMutates(function: String, mutant: ManifestEntry, args: List<Any?>, original: Any?, mutated: Any?) {
        assertEquals(original, compiled.call(function, *args.toTypedArray()), "$function original")
        assertEquals(mutated, compiled.call(function, *args.toTypedArray(), activeId = mutant.id), "$function mutated: ${mutant.description}")
    }

    @Test
    fun unsignedRangesFlipEachBound() {
        val (lower, upper) = mutants("RANGE_BOUNDARY", 1)
        assertEquals("x.toUInt() in lo.toUInt()..hi.toUInt() → lo.toUInt() < x.toUInt() && x.toUInt() <= hi.toUInt()", lower.description)
        assertMutates("uinside", lower, listOf(1, 1, 5), true, false)
        assertMutates("uinside", upper, listOf(5, 1, 5), true, false)
        assertMutates("uinside", upper, listOf(3, 1, 5), true, true)
        // Compared unsigned: -1 is UInt.MAX_VALUE, above the range either way.
        assertMutates("uinside", lower, listOf(-1, 1, 5), false, false)
        assertMutates("ubelow", mutants("RANGE_BOUNDARY", 2)[1], listOf(10L), false, true)
    }

    @Test
    fun steppedRangesLeaveOutTheirFirstElement() {
        val mutant = mutants("RANGE_BOUNDARY", 3).single()
        assertEquals("x in 1..9 step 2 → x != 1 && x in 1..9 step 2", mutant.description)
        assertMutates("stepped", mutant, listOf(1), true, false)
        assertMutates("stepped", mutant, listOf(9), true, true)
        assertMutates("stepped", mutant, listOf(2), false, false)
        val down = mutants("RANGE_BOUNDARY", 4).single()
        assertMutates("steppedDown", down, listOf(10), false, true)
        assertMutates("steppedDown", down, listOf(5), false, false)
    }

    @Test
    fun steppedOperandsAreEvaluatedOnceInOrder() {
        val mutant = mutants("RANGE_BOUNDARY", 9).single()
        // x = 1, range 2..3 step 4: evaluated in source order, four calls either way.
        assertEquals(40, compiled.call("counted", IntArray(1)))
        assertEquals(40, compiled.call("counted", IntArray(1), activeId = mutant.id))
    }

    @Test
    fun unsignedBitwise() {
        assertMutates("uand", mutants("BITWISE", 5).single(), listOf(6, 3), 2, 7)
        assertMutates("ushl", mutants("BITWISE", 6).single(), listOf(8L), 32L, 2L)
        assertMutates("uinv", mutants("BITWISE", 7).single(), listOf(0), -1, 0)
        assertEquals("a.toUInt() and b.toUInt() → a.toUInt() or b.toUInt()", mutants("BITWISE", 5).single().description)
    }
}
