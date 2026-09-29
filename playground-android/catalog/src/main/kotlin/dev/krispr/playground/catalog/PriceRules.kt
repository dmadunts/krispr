package dev.krispr.playground.catalog

class PriceRules(
    private val memberPercent: Int = 5,
    private val taxBasisPoints: Int = 2000,
    private val freeShippingCents: Long = 5000,
    private val shippingCents: Long = 499,
) {
    fun bulkPercent(quantity: Int): Int = when {
        quantity >= 100 -> 20
        quantity >= 50 -> 15
        quantity >= 10 -> 10
        quantity >= 5 -> 5
        else -> 0
    }

    fun lineTotal(p: Product, quantity: Int, member: Boolean): Long {
        require(quantity > 0) { "quantity must be positive" }
        val gross = p.effectivePriceCents * quantity
        var percent = bulkPercent(quantity)
        if (member) percent += memberPercent
        if (percent > 25) percent = 25
        return gross - gross * percent / 100
    }

    fun couponCents(subtotal: Long, coupon: String?): Long {
        if (coupon == null) return 0
        return when {
            coupon == "TENOFF" && subtotal >= 2000 -> 1000
            coupon.startsWith("PCT") -> {
                val pct = coupon.removePrefix("PCT").toIntOrNull() ?: return 0
                if (pct <= 0 || pct > 50) 0 else subtotal * pct / 100
            }
            else -> 0
        }
    }

    fun shipping(subtotal: Long, member: Boolean): Long =
        if (member || subtotal >= freeShippingCents) 0 else shippingCents

    fun tax(amount: Long): Long = (amount * taxBasisPoints + 5000) / 10000

    fun total(lines: List<Pair<Product, Int>>, member: Boolean, coupon: String? = null): Long {
        val subtotal = lines.sumOf { (p, q) -> lineTotal(p, q, member) }
        val discounted = (subtotal - couponCents(subtotal, coupon)).coerceAtLeast(0)
        return discounted + tax(discounted) + shipping(subtotal, member)
    }
}
