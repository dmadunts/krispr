package dev.krispr.compiler

import dev.krispr.runtime.Mutants
import dev.krispr.runtime.Probe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/** The `probe` option (showChanges): each probed site records its value with the mutant off and on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProbeSiteTest {
    private val source = """
        import kotlinx.coroutines.flow.MutableStateFlow
        class Counter {
            var count = 0
            val state = MutableStateFlow(0)
            fun set(n: Int) { count = n }
            fun publish(n: Int) { state.value = n }
        }
        fun adult(age: Int): Boolean { return age >= 18 }
        fun size(n: Int): String { if (n > 10) return "big"; return "small" }
        fun store(n: Int): Int { val c = Counter(); c.set(n); return c.count }
        fun publish(n: Int): Int { val c = Counter(); c.publish(n); return c.state.value }
        fun total(xs: List<Int>): Int = xs.sum() + 1
        class Journal { val lines = mutableListOf<String>(); fun add(line: String) { lines += line } }
        fun remember(s: String): Int { val j = Journal(); j.add(s); return j.lines.size }
    """.trimIndent()

    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(source, operators = listOf("DEFAULTS", "REMOVE_ASSIGNMENT"), probe = true)
    }

    private fun mutant(operator: String, line: Int): Int =
        compiled.mutantsOf(operator).singleOrNull { it.line == line }?.id ?: error("no $operator on line $line in ${compiled.mutants.map { it.line to it.description }}")

    /** The probe file's lines for one call of [function] with mutant [id] probed, and switched on when [active]. */
    private fun observe(id: Int, active: Boolean, function: String, vararg args: Any?): List<String> {
        val file = Files.createTempFile("probe", ".values").toFile()
        val previous = Mutants.probeId
        Mutants.probeId = id
        Probe.out = file
        Probe.reset()
        try {
            compiled.call(function, *args, activeId = if (active) id else Mutants.NONE)
        } finally {
            Mutants.probeId = previous
            Probe.out = null
            Probe.reset()
        }
        return file.readLines().filter { it.startsWith("D") }.map { it.substringAfter('\t') }
    }

    @Test
    fun aReturnValueIsRecorded() {
        val id = mutant("RETURN_VALUE", 8)
        assertEquals(listOf("true"), observe(id, active = false, "adult", 20))
        assertEquals(listOf("false"), observe(id, active = true, "adult", 20))
    }

    @Test
    fun aConditionRecordsTheBranchTaken() {
        val id = mutant("CONDITION_TRUE", 9)
        assertEquals(listOf("false"), observe(id, active = false, "size", 3))
        assertEquals(listOf("true"), observe(id, active = true, "size", 3))
        val boundary = mutant("CONDITIONALS_BOUNDARY", 9)
        assertEquals(listOf("false"), observe(boundary, active = false, "size", 10))
        assertEquals(listOf("true"), observe(boundary, active = true, "size", 10))
    }

    @Test
    fun anAssignmentRecordsTheFieldAfterTheStore() {
        val id = mutant("REMOVE_ASSIGNMENT", 5)
        assertEquals(listOf("7"), observe(id, active = false, "store", 7))
        assertEquals(listOf("0"), observe(id, active = true, "store", 7))
    }

    @Test
    fun aStateHolderStoreRecordsTheValueOrThatItWasSkipped() {
        val id = mutant("REMOVE_ASSIGNMENT", 6)
        assertEquals(listOf("3"), observe(id, active = false, "publish", 3))
        assertEquals(listOf(Probe.SKIPPED), observe(id, active = true, "publish", 3))
    }

    @Test
    fun aStatementWithNoValueIsNotProbed() {
        val id = mutant("REMOVE_CALL", 14)
        assertEquals(emptyList<String>(), observe(id, active = false, "remember", "x"))
    }

    @Test
    fun onlyTheProbedMutantRecords() {
        val id = mutant("RETURN_VALUE", 8)
        assertEquals(emptyList<String>(), observe(id + 1000, active = false, "adult", 20))
        assertEquals(listOf(true, false), listOf(compiled.call("adult", 20), compiled.call("adult", 20, activeId = id)))
    }

    @Test
    fun theOptionOffEmitsNoProbe() {
        fun classes(compiled: Compiled): Map<String, ByteArray> = compiled.result.outputDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .associate { it.relativeTo(compiled.result.outputDirectory).path to it.readBytes() }
        val omitted = classes(Harness.compile(source, operators = listOf("DEFAULTS", "REMOVE_ASSIGNMENT")))
        val off = classes(Harness.compile(source, operators = listOf("DEFAULTS", "REMOVE_ASSIGNMENT"), probe = false))
        val on = classes(compiled)

        assertEquals(omitted.keys, off.keys)
        omitted.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(off.getValue(name)), "$name differs with probe=false") }
        fun mentionsProbe(classes: Map<String, ByteArray>) = classes.values.any { String(it, Charsets.ISO_8859_1).contains("isProbed") }
        assertFalse(mentionsProbe(omitted))
        assertTrue(mentionsProbe(on))
    }

    @Test
    fun probedCodeBehavesTheSame() {
        val plain = Harness.compile(source, operators = listOf("DEFAULTS", "REMOVE_ASSIGNMENT"))
        assertEquals(plain.mutants.map { it.id to it.description }, compiled.mutants.map { it.id to it.description })
        for ((function, args) in listOf("adult" to arrayOf<Any?>(20), "size" to arrayOf<Any?>(3), "store" to arrayOf<Any?>(7), "total" to arrayOf<Any?>(listOf(1, 2)))) {
            for (entry in compiled.mutants) {
                assertEquals(
                    runCatching { plain.call(function, *args, activeId = entry.id) }.getOrElse { it.javaClass },
                    runCatching { compiled.call(function, *args, activeId = entry.id) }.getOrElse { it.javaClass },
                    "$function with ${entry.operator} ${entry.description}",
                )
            }
        }
    }
}
