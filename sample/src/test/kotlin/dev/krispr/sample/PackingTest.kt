package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackingTest {
    private val letter = Parcel.Letter(20)
    private val box = Parcel.Box(1500, fragile = true)
    private val pallet = Parcel.Pallet(40000, boxes = 12)
    private val parcels = mapOf("l" to letter, "b" to box, "p" to pallet)

    @Test
    fun valuePreservingCalls() {
        assertEquals("fragile", Packing.label("  FRAGILE "))
        assertEquals(1, Packing.padding(20))
        assertEquals(15, Packing.padding(1500))
        assertEquals(20, Packing.padding(40000))
        assertEquals(0, Packing.spareBoxes(1))
        assertEquals(10, Packing.spareBoxes(12))
        assertEquals(5, Packing.drift(10, 15))
        assertEquals(listOf(40000, 1500), Packing.heaviest(listOf(letter, box, box, pallet, Parcel.Letter(0)), 2))
        assertEquals(listOf(40000, 1500, 20), Packing.heaviest(listOf(letter, box, box, pallet, Parcel.Letter(0)), 5))
    }

    @Test
    fun bitwise() {
        assertEquals(Packing.HEAVY or Packing.FRAGILE, Packing.flags(box))
        assertEquals(Packing.HEAVY, Packing.flags(pallet))
        assertEquals(0, Packing.flags(letter))
        assertEquals(0, Packing.flags(Parcel.Letter(1000)))
        assertTrue(Packing.isFragile(Packing.FRAGILE))
        assertFalse(Packing.isFragile(Packing.HEAVY))
        assertEquals(Packing.HEAVY, Packing.clearFlags(Packing.HEAVY or Packing.FRAGILE, Packing.FRAGILE))
        assertEquals((3L shl 20) + 3, Packing.trackingCode(3, 7))
        assertEquals(-4, Packing.halve(-7))
        assertEquals(750, Packing.halve(1500))
    }

    @Test
    fun ranges() {
        assertEquals("small", Packing.band(0))
        assertEquals("small", Packing.band(499))
        assertEquals("medium", Packing.band(500))
        assertEquals("medium", Packing.band(1999))
        assertEquals("large", Packing.band(2000))
        assertEquals("large", Packing.band(29999))
        assertEquals("freight", Packing.band(30000))
        assertEquals("freight", Packing.band(-1))
        assertTrue(Packing.inCountdown(1))
        assertTrue(Packing.inCountdown(10))
        assertFalse(Packing.inCountdown(0))
        assertFalse(Packing.inCountdown(11))
        assertTrue(Packing.closed(5))
        assertFalse(Packing.closed(6))
        assertFalse(Packing.closed(22))
        assertTrue(Packing.closed(23))
        assertTrue(Packing.pickupSlot(0))
        assertTrue(Packing.pickupSlot(45))
        assertFalse(Packing.pickupSlot(10))
        assertTrue(Packing.validZone(1u))
        assertTrue(Packing.validZone(9u))
        assertFalse(Packing.validZone(0u))
        assertEquals(5u, Packing.withZoneFlag(1u, 4u))
        assertEquals(20 + 1500 - 10 - 750, Packing.totalGrams(listOf(letter, box)))
    }

    @Test
    fun elvis() {
        assertEquals(1500, Packing.weightOf(parcels, "b"))
        assertEquals(0, Packing.weightOf(parcels, "x"))
        assertEquals("missing", Packing.describe(parcels, "x"))
        assertEquals("letter", Packing.describe(parcels, "l"))
        assertEquals(1520, Packing.knownWeight(parcels, listOf("l", "x", "b")))
    }

    @Test
    fun isBranches() {
        assertEquals("letter", Packing.kind(letter))
        assertEquals("fragile box", Packing.kind(box))
        assertEquals("box", Packing.kind(Parcel.Box(10, fragile = false)))
        assertEquals("pallet of 12", Packing.kind(pallet))
        assertEquals(5000, Packing.surcharge(pallet))
        assertEquals(0, Packing.surcharge("not a parcel"))
        assertEquals(100, Packing.surcharge(box))
    }
}
