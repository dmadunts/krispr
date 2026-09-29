package dev.krispr.playground.units

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test
    fun bytes() {
        assertEquals("1023 B", Units.bytes(1023))
        assertEquals("1.0 KB", Units.bytes(1024))
        assertEquals("1.5 MB", Units.bytes(1024L * 1536))
    }

    @Test
    fun durations() {
        assertEquals("59s", Units.duration(59))
        assertEquals("2m", Units.duration(120))
        assertEquals("2m 5s", Units.duration(125))
        assertEquals("1h 1m", Units.duration(3661))
    }

    @Test
    fun percents() {
        assertEquals(25, Units.percent(1, 4))
        assertEquals(0, Units.percent(1, 0))
    }

    @Test
    fun distances() {
        assertEquals("999 m", Units.distance(999, metric = true))
        assertEquals("1.5 km", Units.distance(1500, metric = true))
        assertEquals("328 ft", Units.distance(100, metric = false))
    }
}
