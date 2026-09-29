package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Deliberately weak: these tests execute the code but barely check it, so the mutants they reach
 * survive. krisprRun should list them.
 */
class TemperatureTest {
    @Test
    fun convertsWithoutCrashing() {
        Temperature.celsiusToFahrenheit(100.0)
    }

    @Test
    fun classifiesSomething() {
        assertNotNull(Temperature.classify(20.0))
    }

    @Test
    fun comfortableRange() {
        Temperature.isComfortable(20.0)
    }
}
