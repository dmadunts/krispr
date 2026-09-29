package dev.krispr.fixture

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Fails with a message-less error, only when a runtime test runs it by unique id. */
class NullMessageFailure {
    @Test
    fun fails() {
        assumeTrue(System.getProperty(RUN) == "true", "run only by TestRunTest")
        throw AssertionError()
    }

    companion object {
        const val RUN = "krispr.fixture.nullMessage"
    }
}
