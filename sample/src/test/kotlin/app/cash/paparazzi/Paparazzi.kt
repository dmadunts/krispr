package app.cash.paparazzi

/**
 * Stands in for Paparazzi, which needs Android: krispr recognises a screenshot test by the library
 * package its class references. See BadgeScreenshotTest.
 */
class Paparazzi {
    fun snapshot(content: Any?) {
        check(content != null)
    }
}
