package dev.krispr.sample

import kotlin.math.abs

sealed interface Parcel {
    val grams: Int

    data class Letter(override val grams: Int) : Parcel
    data class Box(override val grams: Int, val fragile: Boolean) : Parcel
    data class Pallet(override val grams: Int, val boxes: Int) : Parcel
}

/** REMOVE_CHAIN_CALL's value-preserving calls, BITWISE, RANGE_BOUNDARY, ELVIS and SKIP_IS_BRANCH. */
object Packing {
    const val HEAVY = 1
    const val FRAGILE = 2

    fun label(name: String): String = name.trim().lowercase()

    fun padding(grams: Int): Int = (grams / 100).coerceIn(1, 20)

    fun spareBoxes(boxes: Int): Int = maxOf(0, boxes - 2)

    fun drift(expected: Int, actual: Int): Int = abs(expected - actual)

    fun heaviest(parcels: List<Parcel>, limit: Int): List<Int> =
        parcels.map { it.grams }.filter { it > 0 }.sortedDescending().distinct().take(limit)

    fun flags(parcel: Parcel): Int {
        var bits = 0
        if (parcel.grams > 1000) bits = bits or HEAVY
        if (parcel is Parcel.Box && parcel.fragile) bits = bits or FRAGILE
        return bits
    }

    fun isFragile(flags: Int): Boolean = flags and FRAGILE != 0

    fun clearFlags(flags: Int, cleared: Int): Int = flags and cleared.inv()

    fun trackingCode(route: Long, seq: Long): Long = (route shl 20) xor (seq ushr 1)

    fun halve(grams: Int): Int = grams shr 1

    fun band(grams: Int): String = when {
        grams in 0..499 -> "small"
        grams in 500..<2000 -> "medium"
        grams in 2000 until 30000 -> "large"
        else -> "freight"
    }

    fun inCountdown(day: Int): Boolean = day in 10 downTo 1

    fun closed(hour: Int): Boolean = hour !in 6..22

    /** A `step` range: one mutant leaves out its first slot. */
    fun pickupSlot(minute: Int): Boolean = minute in 0..45 step 15

    /** UInt: RANGE_BOUNDARY compares unsigned, BITWISE swaps `or`/`and`. */
    fun validZone(zone: UInt): Boolean = zone in 1u..9u

    fun withZoneFlag(flags: UInt, flag: UInt): UInt = flags or flag

    /** Loops over ranges are left alone. */
    fun totalGrams(parcels: List<Parcel>): Int {
        var total = 0
        for (i in 0 until parcels.size) total += parcels[i].grams
        for (i in parcels.size - 1 downTo 0) total -= parcels[i].grams / 2
        return total
    }

    fun weightOf(parcels: Map<String, Parcel>, id: String): Int = parcels[id]?.grams ?: 0

    fun describe(parcels: Map<String, Parcel>, id: String): String {
        val parcel = parcels[id] ?: return "missing"
        return kind(parcel)
    }

    fun knownWeight(parcels: Map<String, Parcel>, ids: List<String>): Int {
        var total = 0
        for (id in ids) {
            val parcel = parcels[id] ?: continue
            total += parcel.grams
        }
        return total
    }

    fun kind(parcel: Parcel): String = when (parcel) {
        is Parcel.Letter -> "letter"
        is Parcel.Box -> if (parcel.fragile) "fragile box" else "box"
        is Parcel.Pallet -> "pallet of ${parcel.boxes}"
    }

    fun surcharge(item: Any): Int = when (item) {
        is Parcel.Pallet -> 5000
        !is Parcel -> 0
        else -> 100
    }
}
