package dev.krispr.sample

import kotlinx.coroutines.delay

/** Suspend functions: only the user-written comparisons are mutated, never the state machine. */
class Inventory(private val stock: Map<String, Int>) {
    suspend fun available(sku: String, wanted: Int): Boolean {
        delay(1)
        val have = stock[sku] ?: 0
        return wanted > 0 && have >= wanted
    }

    suspend fun reservable(order: Map<String, Int>): Int {
        var lines = 0
        for ((sku, quantity) in order) {
            if (available(sku, quantity)) lines = lines + 1
        }
        return lines
    }
}
