package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescriptionsTest {
    @Test
    fun shortDescriptionsAreLeftAlone() {
        assertEquals("a < b → a <= b", fitDescription("a < b → a <= b"))
        assertEquals("(removed)", fitDescription("(removed)"))
    }

    @Test
    fun aChangeDeepInALongExpressionStaysVisible() {
        // clikt Option.kt:178 as the diff-mode replay printed it: both sides were cut to the same 77 characters,
        // so the reader could not tell what the mutant did (docs/evidence.md, "What to fix next").
        val head = "context.valueSource?.getValues(context, this)?.map { OptionInvocation(\"\", it) }"
        val tail = "?.ifEmpty { null }"
        val before = "$head?.filter { it.values.isNotEmpty() }$tail"
        val after = "$head$tail"

        val fitted = fitDescription("$before → $after")

        val (left, right) = fitted.split(" → ")
        assertTrue(left != right, fitted)
        assertTrue("?.filter { it.values.isNotEmpty() }" in left, fitted)
        assertTrue(left.startsWith("…") && right.startsWith("…"), "shared prefix folded: $fitted")
        assertTrue(left.endsWith(tail) && right.endsWith(tail), "short shared suffix kept whole: $fitted")
        assertTrue(left.length <= 100 && right.length <= 100, fitted)
    }

    @Test
    fun aLongSharedSuffixIsFoldedToo() {
        val shared = ") " + "x".repeat(120)
        val fitted = fitDescription("if (a && b$shared → if (a || b$shared")
        val (left, right) = fitted.split(" → ")
        assertTrue(left.startsWith("if (a && b) x") && right.startsWith("if (a || b) x"), fitted)
        assertTrue(left.endsWith("…") && right.endsWith("…"), fitted)
        assertTrue(left.length <= 100 && right.length <= 100, fitted)
    }

    @Test
    fun sidesWithinTheLimitAreNeverFolded() {
        val shared = "x".repeat(60)
        val text = "if (a && b) $shared → if (a || b) $shared"
        assertEquals(text, fitDescription(text))
    }

    @Test
    fun aRemovedCallKeepsItsHeadAndTheMarker() {
        val call = "record(" + "a, ".repeat(50) + "z)"
        val fitted = fitDescription("$call → (removed)")
        assertTrue(fitted.endsWith(" → (removed)"), fitted)
        assertTrue(fitted.startsWith("record(a, a, "), fitted)
        assertTrue(fitted.length <= 100 + " → (removed)".length, fitted)
    }

    @Test
    fun onlyTheSharedPartsAreFolded() {
        // The prefix "if (" is shorter than the context, so nothing is folded at the front; the difference is
        // in the middle and both sides fit once the long shared tail is folded.
        val tail = " && " + "flag".repeat(30)
        val fitted = fitDescription("if (a < b)$tail → if (a <= b)$tail")
        assertTrue(fitted.startsWith("if (a < b) && "), fitted)
        assertTrue(" → if (a <= b) && " in fitted, fitted)
    }
}
