package dev.krispr.sample.kmp

actual fun platformTag(): String = "jvm"

/** JVM-only: picks the newest version a range accepts from lines of a lock file. */
fun newestAccepted(range: Version, lines: List<String>): Version? =
    lines.mapNotNull { Version.parse(it.trim()) }.filter { range.accepts(it) }.maxOrNull()
