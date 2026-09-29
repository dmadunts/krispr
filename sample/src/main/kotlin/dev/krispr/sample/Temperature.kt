package dev.krispr.sample

/** Covered only by the deliberately weak TemperatureTest, so its mutants survive. */
object Temperature {
    fun celsiusToFahrenheit(celsius: Double): Double = celsius * 9 / 5 + 32

    fun classify(celsius: Double): String = when {
        celsius < 0.0 -> "freezing"
        celsius < 15.0 -> "cold"
        celsius < 25.0 -> "mild"
        else -> "hot"
    }

    fun isComfortable(celsius: Double): Boolean = celsius >= 18.0 && celsius <= 24.0
}
