package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class MutantExclusionsTest {
    private val root = File("/repo")
    private val module = File("/repo/app")
    private val cart = MutantExclusions.Candidate(
        file = "/repo/app/src/main/kotlin/com/example/Cart.kt",
        line = 14,
        operator = "MATH",
        declaration = "com.example.Cart.total(kotlin.Int)",
    )

    private fun excludes(rules: String, mutant: MutantExclusions.Candidate = cart): Boolean =
        MutantExclusions(MutantExclusions.parse(rules)).excludes(mutant, listOf(module, root)) != null

    @Test
    fun files() {
        assertTrue(excludes("Cart.kt"))
        assertTrue(excludes("file=*.kt"))
        assertTrue(excludes("src/main/**/Cart.kt"))
        assertTrue(excludes("app/src/**"))
        assertTrue(excludes("**/example/*.kt"))
        assertFalse(excludes("src/*/Cart.kt"))
        assertFalse(excludes("Carts.kt"))
    }

    @Test
    fun classesFunctionsOperatorsAndLines() {
        assertTrue(excludes("class=com.example.Cart"))
        assertTrue(excludes("class=Cart"))
        assertTrue(excludes("class=com.example.*"))
        assertFalse(excludes("class=Order"))
        assertTrue(excludes("function=tot*"))
        assertFalse(excludes("function=subtotal"))
        assertTrue(excludes("operator=math"))
        assertTrue(excludes("lines=10-20"))
        assertTrue(excludes("lines=3,14"))
        assertFalse(excludes("lines=15-20"))
    }

    @Test
    fun everyTermOfARuleMustMatchAndAnyRuleExcludes() {
        assertTrue(excludes("Cart.kt lines=14 operator=MATH"))
        assertFalse(excludes("Cart.kt lines=15"))
        assertTrue(excludes("# comment\n\nOrder.kt\nfunction=total  # trailing comment"))
    }

    @Test
    fun topLevelCodeHasThePackageAsItsClass() {
        val topLevel = MutantExclusions.Candidate(cart.file, 3, "NEGATE_IF", "com.example.discount(kotlin.Long)")
        assertTrue(excludes("class=com.example function=discount", topLevel))
        assertEquals("<init>", MutantExclusions.member("com.example.Cart.<init>(kotlin.Int)"))
    }

    @Test
    fun badRulesNameTheirLine() {
        val error = assertThrows<IllegalArgumentException> { MutantExclusions.parse("Cart.kt\nmethod=total", "x/.krispr-exclude") }
        assertTrue("x/.krispr-exclude:2" in error.message.orEmpty(), error.message)
        assertThrows<IllegalArgumentException> { MutantExclusions.parse("lines=a-b") }
    }
}
