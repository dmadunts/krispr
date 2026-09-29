package dev.krispr.playground.settings

import android.content.Context

enum class Theme { LIGHT, DARK, SYSTEM }

/** Preferences through SharedPreferences, so every test of it runs under Robolectric. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme: Theme
        get() = prefs.getString(KEY_THEME, null)?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT, 1f)
        set(value) = prefs.edit().putFloat(KEY_FONT, value.coerceIn(MIN_FONT, MAX_FONT)).apply()

    var syncHours: Int
        get() = prefs.getInt(KEY_SYNC, 6)
        set(value) {
            require(value in 1..48) { "sync interval out of range" }
            prefs.edit().putInt(KEY_SYNC, value).apply()
        }

    fun isNight(systemNight: Boolean): Boolean = when (theme) {
        Theme.LIGHT -> false
        Theme.DARK -> true
        Theme.SYSTEM -> systemNight
    }

    fun increaseFont(): Float {
        fontScale = fontScale + STEP
        return fontScale
    }

    fun decreaseFont(): Float {
        fontScale = fontScale - STEP
        return fontScale
    }

    fun syncDue(lastSyncMillis: Long, nowMillis: Long): Boolean =
        nowMillis - lastSyncMillis >= syncHours * HOUR

    fun reset() = prefs.edit().clear().apply()

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_FONT = "font"
        const val KEY_SYNC = "sync"
        const val MIN_FONT = 0.8f
        const val MAX_FONT = 1.6f
        const val STEP = 0.2f
        const val HOUR = 3_600_000L
    }
}
