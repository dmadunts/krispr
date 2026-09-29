package dev.krispr.playground.pricing

import android.content.Context

/**
 * Formats prices and keeps one canonical string per amount in a process-wide pool, so equal prices share
 * one instance. format() always computes the text, and the pool hands back the first one it kept. Once any
 * run has filled the pool (the reuse check's, with no mutant, or a survivor's), a sandbox that keeps this
 * class returns the pooled text: a mutant of compute() is reached but hidden there, and survives, while a
 * fresh JVM kills it. Tests only ever check format(), so mutants of the pool size check survive anywhere.
 */
object MoneyFormat {
    private val pool = HashMap<Long, String>()

    fun format(cents: Long): String {
        val text = compute(cents)
        if (pool.size >= MAX_ENTRIES) return text
        return pool.getOrPut(cents) { text }
    }

    fun compute(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val whole = abs / 100
        val frac = abs % 100
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = "£$grouped." + frac.toString().padStart(2, '0')
        return if (negative) "-$body" else body
    }

    const val MAX_ENTRIES = 256
}

/** Exchange rates parsed once per process from a raw table, the `by lazy` variant of the same trap. */
class Rates(private val context: Context) {
    fun convert(cents: Long, currency: String): Long {
        val rate = table[currency] ?: return cents
        return Math.round(cents * rate)
    }

    fun symbol(currency: String): String = symbols[currency] ?: currency

    fun appName(): String = context.applicationInfo.packageName

    companion object {
        private const val RAW = "EUR=1.17;USD=1.27;JPY=190.5;CHF=1.12"

        val table: Map<String, Double> by lazy {
            RAW.split(';').associate { entry ->
                val (code, rate) = entry.split('=')
                code to rate.toDouble()
            }
        }

        val symbols: Map<String, String> by lazy {
            mapOf("EUR" to "€", "USD" to "$", "JPY" to "¥")
        }
    }
}
