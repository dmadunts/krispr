package dev.krispr.sample

/** Only a screenshot test reaches this, so every mutant here is NOT_MEASURED; see BadgeScreenshotTest. */
object Badge {
    fun label(count: Int): String = if (count > 99) "99+" else count.toString()
}
