package dev.krispr.playground.checkout

enum class Zone { LOCAL, NATIONAL, INTERNATIONAL }

data class Parcel(val grams: Int, val lengthCm: Int, val widthCm: Int, val heightCm: Int)

object Shipping {
    fun volumetricGrams(p: Parcel): Int = p.lengthCm * p.widthCm * p.heightCm / 5

    fun chargeableGrams(p: Parcel): Int = maxOf(p.grams, volumetricGrams(p))

    fun cost(p: Parcel, zone: Zone, express: Boolean): Long {
        val grams = chargeableGrams(p)
        val base = when (zone) {
            Zone.LOCAL -> 300L
            Zone.NATIONAL -> 500L
            Zone.INTERNATIONAL -> 1500L
        }
        val perKilo = when (zone) {
            Zone.LOCAL -> 50L
            Zone.NATIONAL -> 100L
            Zone.INTERNATIONAL -> 400L
        }
        val kilos = (grams + 999) / 1000
        var cost = base + perKilo * (kilos - 1).coerceAtLeast(0)
        if (express) cost = cost * 3 / 2
        return cost
    }

    fun deliveryDays(zone: Zone, express: Boolean): Int {
        val days = when (zone) {
            Zone.LOCAL -> 2
            Zone.NATIONAL -> 3
            Zone.INTERNATIONAL -> 8
        }
        return if (express) maxOf(1, days / 2) else days
    }
}
