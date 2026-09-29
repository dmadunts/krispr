package dev.krispr.playground.session

import android.os.SystemClock

/** Refreshes at most once per interval, measured on the main thread's uptime clock. */
object RefreshThrottle {
    const val INTERVAL = 30_000L
    private var lastRefresh = Long.MIN_VALUE
    var refreshes = 0
        private set

    fun maybeRefresh(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (lastRefresh != Long.MIN_VALUE && now - lastRefresh < INTERVAL) return false
        lastRefresh = now
        refreshes++
        return true
    }

    fun remainingMillis(): Long {
        if (lastRefresh == Long.MIN_VALUE) return 0
        val left = INTERVAL - (SystemClock.uptimeMillis() - lastRefresh)
        return if (left > 0) left else 0
    }
}
