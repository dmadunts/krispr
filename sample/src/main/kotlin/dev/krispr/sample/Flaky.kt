package dev.krispr.sample

/**
 * Fixtures for krispr's flake guards; see FlakyTest. Every mutant here is UNKNOWN: [backoff]'s only test
 * fails without any mutant, and [jitter] is reached only in the recording run, so its mutants are never
 * activated.
 */
object Flaky {
    fun backoff(attempt: Int): Long = attempt * 100L

    fun jitter(millis: Long): Long = millis + 7
}
