package dev.krispr.playground.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        store = SettingsStore(RuntimeEnvironment.getApplication())
        store.reset()
    }

    @Test
    fun themeDefaultsToSystem() {
        assertEquals(Theme.SYSTEM, store.theme)
        assertTrue(store.isNight(systemNight = true))
        assertFalse(store.isNight(systemNight = false))
    }

    @Test
    fun themeRoundTrips() {
        store.theme = Theme.DARK
        assertEquals(Theme.DARK, store.theme)
        assertTrue(store.isNight(systemNight = false))
        store.theme = Theme.LIGHT
        assertFalse(store.isNight(systemNight = true))
    }

    @Test
    fun fontSteps() {
        assertEquals(1f, store.fontScale, 0f)
        assertEquals(1.2f, store.increaseFont(), 1e-6f)
        assertEquals(1.4f, store.increaseFont(), 1e-6f)
        assertEquals(1.6f, store.increaseFont(), 1e-6f)
        assertEquals(1.6f, store.increaseFont(), 1e-6f)
        store.fontScale = 0.9f
        assertEquals(0.8f, store.decreaseFont(), 1e-6f)
        assertEquals(0.8f, store.decreaseFont(), 1e-6f)
    }

    @Test
    fun sync() {
        assertEquals(6, store.syncHours)
        store.syncHours = 1
        assertEquals(1, store.syncHours)
        store.syncHours = 48
        assertTrue(store.syncDue(0, 48 * SettingsStore.HOUR))
        assertFalse(store.syncDue(1, 48 * SettingsStore.HOUR))
        assertThrows(IllegalArgumentException::class.java) { store.syncHours = 0 }
        assertThrows(IllegalArgumentException::class.java) { store.syncHours = 49 }
    }
}
