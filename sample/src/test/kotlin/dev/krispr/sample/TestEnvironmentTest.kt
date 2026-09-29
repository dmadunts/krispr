package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** The krispr forks must see the same system properties, environment and working directory as `test`. */
class TestEnvironmentTest {
    @Test
    fun seesTheTestTaskSettings() {
        assertEquals("celsius", System.getProperty("sample.unit"))
        assertEquals("eu", System.getenv("SAMPLE_REGION"))
        assertEquals("sample", File("").absoluteFile.name)
    }
}
