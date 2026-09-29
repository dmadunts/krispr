package dev.krispr.fixture

import app.cash.paparazzi.Paparazzi

class PaparazziScreenshotTest {
    private val paparazzi = Paparazzi()

    fun screen() = listOf(1).map { paparazzi.snapshot("screen $it") }
}

/** A helper in the style of nowinandroid's `captureMultiTheme`. */
object ScreenshotHelper {
    fun capture(name: String) = Paparazzi().snapshot(name)
}

class HelperScreenshotTest {
    fun screen() = ScreenshotHelper.capture("screen")
}

/** Shared test data in a module that also holds screenshot helpers. */
object TestData {
    fun sample() = 42
    fun snapshotSample() = Paparazzi().snapshot("sample")
}

class TestDataUserTest {
    fun uses() = TestData.sample() + 1
}

class PlainTest {
    fun add() = 1 + 1
}
