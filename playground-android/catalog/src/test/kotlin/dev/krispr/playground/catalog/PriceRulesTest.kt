package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JUnit: the rules need no Android API, so a mutant only these tests reach costs no sandbox.
class PriceRulesTest {
    private val rules = PriceRules()

    @Test
    fun bulkTiers() {
        assertEquals(0, rules.bulkPercent(4))
        assertEquals(5, rules.bulkPercent(5))
        assertEquals(10, rules.bulkPercent(10))
        assertEquals(15, rules.bulkPercent(50))
        assertEquals(20, rules.bulkPercent(100))
    }

    @Test
    fun lineTotals() {
        assertEquals(12990L, rules.lineTotal(Fixtures.novel, 10, member = false) + 1299)
        assertEquals(1235L, rules.lineTotal(Fixtures.novel, 1, member = true))
        assertEquals(67992L, rules.lineTotal(Fixtures.drill, 10, member = true))
    }

    @Test
    fun coupons() {
        assertEquals(1000L, rules.couponCents(2000, "TENOFF"))
        assertEquals(0L, rules.couponCents(1999, "TENOFF"))
        assertEquals(300L, rules.couponCents(1000, "PCT30"))
        assertEquals(0L, rules.couponCents(1000, "PCT60"))
        assertEquals(0L, rules.couponCents(1000, null))
    }

    @Test
    fun totals() {
        assertEquals(1299L + 260 + 499, rules.total(listOf(Fixtures.novel to 1), member = false))
        assertEquals(0L + 0, rules.total(emptyList(), member = true))
    }
}
