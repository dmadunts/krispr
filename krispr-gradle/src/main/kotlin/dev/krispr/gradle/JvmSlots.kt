package dev.krispr.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The build-wide count of running krispr test JVMs (recording, baseline, and each mutant's fork or reused
 * worker run), shared by every module's tasks. Gradle runs several modules' `krisprRun` at once, and each
 * runs its own threads, so a per-module limit alone let 4 workers × 4 threads start 16 JVMs on 10 cores
 * (#31). A slot is held for one JVM run, not for a task: Gradle's `maxParallelUsages` would count tasks.
 *
 * Each request names its module's cap ([KrisprExtension.maxConcurrentJvms]) and waits until fewer than
 * that many JVMs run build-wide. When a slot frees, it goes to the waiting request whose owner (a task)
 * holds the fewest slots, the earliest of those first, so modules take turns rather than queueing behind
 * the one that asked first (#41). The chosen request waits for its own cap even when a later one would
 * fit, so a module with a lower cap is not starved; with one cap everywhere (the default, or
 * `-Pkrispr.maxConcurrentJvms`) this is a fair semaphore.
 */
abstract class JvmSlots : BuildService<BuildServiceParameters.None> {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private class Ticket(val owner: String)

    /** Waiting requests in arrival order. */
    private val queue = ArrayList<Ticket>()
    private val held = HashMap<String, Int>()
    private var running = 0

    /** The most JVMs that ran at once in this build; logged, and read by the functional test. */
    @Volatile
    var peak = 0
        private set

    /** One held slot; [close] gives it back. */
    inner class Slot internal constructor(private val owner: String) : AutoCloseable {
        private val acquired = System.nanoTime()
        private var open = true
        private var passing = false

        val heldMillis: Long get() = (System.nanoTime() - acquired) / 1_000_000

        /** Whether another owner is waiting for a slot. */
        fun contended(): Boolean = lock.withLock { queue.any { it.owner != owner } }

        /**
         * Whether to pass this slot on now, without waiting out a quantum: this owner holds more than one
         * and another waits holding none, so every waiting module gets a JVM. True commits the caller to
         * [close] it; the owner stops counting it at once, so its other slots stay.
         */
        fun passToOwnerWithNone(): Boolean = lock.withLock {
            if (!open || passing || heldBy(owner) <= 1 || queue.none { it.owner != owner && heldBy(it.owner) == 0 }) return false
            passing = true
            unhold(owner)
            true
        }

        override fun close() {
            lock.withLock {
                if (!open) return
                open = false
                running--
                if (!passing) unhold(owner)
                changed.signalAll()
            }
        }
    }

    /** Runs [block] holding one slot of [cap]; see [acquire]. */
    fun <T> withSlot(cap: Int, owner: String = "", onWait: () -> Unit = {}, block: () -> T): T =
        acquire(cap, owner, onWait).use { block() }

    /**
     * Takes one slot of [cap] for [owner], waiting until it is this request's turn and fewer than [cap]
     * JVMs run. When no slot is free straight away, [onWait] runs first (outside the lock), so a module can
     * shut its idle JVMs while others use the machine.
     */
    fun acquire(cap: Int, owner: String = "", onWait: () -> Unit = {}): Slot {
        val limit = cap.coerceAtLeast(1)
        val ticket = Ticket(owner)
        var waited = false
        lock.withLock {
            queue.add(ticket)
            try {
                while (next() !== ticket || running >= limit) {
                    if (!waited) {
                        waited = true
                        lock.unlock()
                        try {
                            onWait()
                        } finally {
                            lock.lock()
                        }
                        continue
                    }
                    changed.await()
                }
            } catch (e: Throwable) {
                // Interrupted (the task was cancelled): give up the place in line.
                queue.remove(ticket)
                changed.signalAll()
                throw e
            }
            queue.remove(ticket)
            running++
            held.merge(owner, 1, Int::plus)
            peak = maxOf(peak, running)
            // The next in line may fit too, under its own cap.
            changed.signalAll()
            return Slot(owner)
        }
    }

    /** The request served next: of the owners holding the fewest slots, the earliest. */
    private fun next(): Ticket? = queue.minByOrNull { heldBy(it.owner) }

    private fun heldBy(owner: String): Int = held[owner] ?: 0

    private fun unhold(owner: String) {
        held.merge(owner, -1, Int::plus)
        if (held[owner] == 0) held.remove(owner)
    }

    companion object {
        const val NAME = "krisprJvmSlots"
    }
}
