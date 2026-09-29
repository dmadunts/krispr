package dev.krispr.playground.units

object Units {
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = n.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[i])
    }

    fun duration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            s == 0L -> "${m}m"
            else -> "${m}m ${s}s"
        }
    }

    fun percent(part: Long, whole: Long): Int = if (whole <= 0) 0 else (part * 100 / whole).toInt()

    fun distance(meters: Int, metric: Boolean): String =
        if (metric) {
            if (meters < 1000) "$meters m" else "${meters / 1000}.${meters % 1000 / 100} km"
        } else {
            val feet = meters * 328 / 100
            if (feet < 5280) "$feet ft" else "${feet / 5280} mi"
        }
}
