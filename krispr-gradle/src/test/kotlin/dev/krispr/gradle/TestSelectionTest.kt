package dev.krispr.gradle

import dev.krispr.fixture.PlainTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TestSelectionTest {
    private fun test(className: String, method: String = "m", millis: Long = 1) = RecordedTest("[id]", className, method, millis)

    @Test
    fun patternsFollowGradleTestsSyntax() {
        val simple = TestSelection.patternRegex("FooTest")
        assertTrue(simple.matches("com.example.FooTest"))
        assertTrue(simple.matches("FooTest"))
        assertTrue(simple.matches("com.example.FooTest\$Nested"))
        assertFalse(simple.matches("com.example.BarFooTest"))

        val wildcard = TestSelection.patternRegex("com.example.*Screenshot*")
        assertTrue(wildcard.matches("com.example.ui.HomeScreenshotTest"))
        assertFalse(wildcard.matches("org.example.HomeScreenshotTest"))

        assertTrue(TestSelection.patternRegex("com.example.FooTest.slow*").matches("com.example.FooTest.slowOne"))
    }

    @Test
    fun exclusionReasonsNameTheirCause() {
        val selection = TestSelection(mapOf("com.example.Shots" to "Paparazzi"), listOf("*IntegrationTest", "com.example.FooTest.slow"))
        assertEquals("screenshot test (Paparazzi)", selection.exclusionReason(test("com.example.Shots\$Nested")))
        assertEquals("excludeTests '*IntegrationTest'", selection.exclusionReason(test("com.example.DbIntegrationTest")))
        assertEquals("excludeTests 'com.example.FooTest.slow'", selection.exclusionReason(test("com.example.FooTest", "slow")))
        assertNull(selection.exclusionReason(test("com.example.FooTest", "fast")))
        assertNull(selection.exclusionReason(test("com.example.FooTest", "")))
    }

    @Test
    fun slowLeafTestsMayNotKill() {
        val selection = TestSelection(emptyMap(), emptyList(), slowThresholdMillis = 2000)
        assertEquals("slow test (2500 ms)", selection.exclusionReason(test("com.example.DbTest", millis = 2500)))
        assertNull(selection.exclusionReason(test("com.example.DbTest", millis = 2000)))
        // A class's duration is the sum of its tests.
        assertNull(selection.exclusionReason(test("com.example.DbTest", "", millis = 2500), leaf = false))
        assertNull(TestSelection(emptyMap(), emptyList()).exclusionReason(test("com.example.DbTest", millis = 2500)))
        // Framework set-up (Robolectric's sandbox, say) does not count; a slow first test of a class does.
        assertNull(selection.exclusionReason(RecordedTest("[id]", "com.example.DbTest", "m", millis = 3800, ownMillis = 150)))
        assertEquals("slow test (3700 ms)", selection.exclusionReason(RecordedTest("[id]", "com.example.DbTest", "m", millis = 3800, ownMillis = 3700)))
    }

    @Test
    fun quarantinedTestsNeverKill() {
        val selection = TestSelection(emptyMap(), listOf("*Test"), quarantinePatterns = listOf("com.example.FlakyTest.sometimes"))
        assertEquals("quarantinedTests 'com.example.FlakyTest.sometimes'", selection.exclusionReason(test("com.example.FlakyTest", "sometimes")))
        assertEquals("excludeTests '*Test'", selection.exclusionReason(test("com.example.FlakyTest", "always")))
    }

    @Test
    fun detectsOnlyClassesThatUseAScreenshotLibraryDirectly() {
        val classes = File(PlainTest::class.java.protectionDomain.codeSource.location.toURI())
        val found = ScreenshotTests.detect(listOf(classes))
        assertEquals("Paparazzi", found["dev.krispr.fixture.PaparazziScreenshotTest"])
        assertEquals("Paparazzi", found["dev.krispr.fixture.ScreenshotHelper"])
        // Calling a helper that captures is not using the library: a shared helper would take out every test.
        assertFalse("dev.krispr.fixture.HelperScreenshotTest" in found)
        assertFalse("dev.krispr.fixture.TestDataUserTest" in found)
        assertFalse("dev.krispr.fixture.PlainTest" in found)
        assertFalse("dev.krispr.gradle.TestSelectionTest" in found)
    }
}
