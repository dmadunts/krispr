package dev.krispr.sample

/** Generated equals/hashCode/toString/copy/componentN must produce no mutants. */
data class Money(val cents: Long, val currency: String) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "currency mismatch" }
        return Money(cents + other.cents, currency)
    }

    fun isPositive(): Boolean = cents > 0L
}
