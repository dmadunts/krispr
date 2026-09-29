package dev.krispr.sample

class PriceCalculator {
    fun discountPercent(quantity: Int): Int = when {
        quantity >= 100 -> 20
        quantity >= 10 -> 10
        quantity > 0 -> 0
        else -> throw IllegalArgumentException("quantity must be positive")
    }

    fun total(unitCents: Long, quantity: Int): Long {
        val gross = unitCents * quantity
        return gross - gross * discountPercent(quantity) / 100
    }

    /** `left - 10L → left + 10L` never terminates: the mutant is killed by the per-mutant timeout. */
    fun fullPacks(quantity: Int): Int {
        var packs = 0
        var left = quantity.toLong()
        while (left >= 10L) {
            left = left - 10L
            packs = packs + 1
        }
        return packs
    }
}
