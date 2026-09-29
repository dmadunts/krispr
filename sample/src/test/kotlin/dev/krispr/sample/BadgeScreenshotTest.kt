package dev.krispr.sample

import app.cash.paparazzi.Paparazzi
import org.junit.jupiter.api.Test

/** A screenshot test: it may not kill mutants unless `useScreenshotTests` is set. */
class BadgeScreenshotTest {
    private val paparazzi = Paparazzi()

    @Test
    fun badge() {
        paparazzi.snapshot(Badge.label(120))
    }
}
