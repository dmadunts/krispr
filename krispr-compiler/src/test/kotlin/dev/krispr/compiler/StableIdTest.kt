package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Ids hash (file, declaration, operator, ordinal), so editing one declaration leaves the others alone. */
class StableIdTest {
    private val before = """
        fun first(a: Long, b: Long): Long = a + b
        fun second(a: Long, b: Long): Long = a * b - 1L
        class Holder(val v: Long) {
            fun third(x: Long): Boolean = x > v
            fun third(x: Int): Boolean = x > 0
        }
    """.trimIndent()

    // `first` gains a mutant and moves down a line; everything after it shifts.
    private val after = """
        // a new comment
        fun first(a: Long, b: Long): Long = a + b + 2L
        fun second(a: Long, b: Long): Long = a * b - 1L
        class Holder(val v: Long) {
            fun third(x: Long): Boolean = x > v
            fun third(x: Int): Boolean = x > 0
        }
    """.trimIndent()

    private fun idsBy(compiled: Compiled) =
        compiled.mutants.groupBy({ it.declaration + " " + it.description }, { it.id }).mapValues { it.value.single() }

    @Test
    fun editingOneFunctionKeepsTheOtherIds() {
        val old = idsBy(Harness.compile(before))
        val new = idsBy(Harness.compile(after))
        val untouched = old.keys.filter { !it.startsWith("first(") }
        assertEquals(old.size - 1, untouched.size, old.keys.toString())
        for (key in untouched) assertEquals(old[key], new[key], key)
        assertEquals(old.size + 1, new.size, new.keys.toString())
    }

    @Test
    fun onlyTheEditedDeclarationsHashChanges() {
        fun hashes(source: String) = Harness.compile(source).mutants.associate { it.declaration to it.hash }
        val old = hashes(before)
        val new = hashes(after)
        assertEquals(old.keys, new.keys)
        for ((declaration, hash) in old) {
            if (declaration.startsWith("first(")) assertNotEquals(hash, new[declaration]) else assertEquals(hash, new[declaration], declaration)
        }
        assertEquals(1, old.values.count { it == old.getValue("first(kotlin.Long,kotlin.Long)") }, old.toString())
    }

    @Test
    fun overloadsAndOrdinalsGetDistinctIds() {
        val compiled = Harness.compile(before)
        assertEquals(compiled.mutants.size, compiled.mutants.map { it.id }.toSet().size)
        val overloads = compiled.mutants.filter { it.declaration.startsWith("Holder.third(") && it.operator == "CONDITIONALS_BOUNDARY" }
        assertEquals(setOf("Holder.third(Holder,kotlin.Long)", "Holder.third(Holder,kotlin.Int)"), overloads.map { it.declaration }.toSet())
        assertNotEquals(overloads[0].id, overloads[1].id)
    }
}
