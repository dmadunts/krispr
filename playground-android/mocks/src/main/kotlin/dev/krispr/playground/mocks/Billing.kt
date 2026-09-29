package dev.krispr.playground.mocks

class Billing(private val analytics: Analytics, private val onCharge: (Long) -> Unit) {
    fun renew(tier: Tier, months: Int, loyaltyYears: Int): Long {
        if (tier.monthlyCents == 0L || months <= 0) return 0
        var total = tier.monthlyCents * months
        if (months >= 12) total -= tier.monthlyCents * 2
        if (loyaltyYears >= 3) total = total * 90 / 100
        onCharge(total)
        analytics.track("renew", mapOf("tier" to tier.name, "total" to total))
        return total
    }
}
