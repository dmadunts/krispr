package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A trailing `// krispr:ignore` drops the mutants of its line and leaves the ids of the others alone. */
class IgnoreCommentTest {
    private fun source(comment: String) = """
        fun price(base: Long, tax: Long): Long {
            val gross = base + tax $comment
            return gross * 2L
        }
    """.trimIndent()

    @Test
    fun ignoredLineHasNoMutantsAndTheOthersKeepTheirIds() {
        val plain = Harness.compile(source("")).mutants
        val ignored = Harness.compile(source("// krispr:ignore retry arithmetic")).mutants

        assertEquals(listOf("base + tax → base - tax", "gross * 2L → gross / 2L"), plain.map { it.description })
        assertEquals(listOf("gross * 2L → gross / 2L"), ignored.map { it.description })
        assertEquals(plain.single { it.line == 3 }.id, ignored.single().id)
    }
}
