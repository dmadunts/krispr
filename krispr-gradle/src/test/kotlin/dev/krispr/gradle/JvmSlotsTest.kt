package dev.krispr.gradle

import org.gradle.api.services.BuildServiceParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JvmSlotsTest {
    private fun slots() = object : JvmSlots() {
        override fun getParameters(): BuildServiceParameters.None = throw UnsupportedOperationException()
    }

    @Test
    fun neverRunsMoreThanTheCapAcrossCallers() {
        val slots = slots()
        val running = AtomicInteger()
        val seen = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        List(40) {
            pool.submit {
                slots.withSlot(3) {
                    seen.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(5)
                    running.decrementAndGet()
                }
            }
        }.forEach { it.get() }
        pool.shutdown()
        assertEquals(3, seen.get())
        assertEquals(3, slots.peak)
    }

    @Test
    fun aLowerCapWaitsUntilFewerRunBuildWide() {
        val slots = slots()
        val release = CountDownLatch(1)
        val holding = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(3)
        repeat(2) {
            pool.submit { slots.withSlot(4) { holding.countDown(); release.await() } }
        }
        holding.await()
        val waited = AtomicInteger()
        val lowCap = pool.submit { slots.withSlot(2, onWait = { waited.incrementAndGet() }) { slots.peak } }
        Thread.sleep(100)
        assertTrue(!lowCap.isDone, "a module capped at 2 must wait while 2 JVMs run")
        release.countDown()
        lowCap.get(5, TimeUnit.SECONDS)
        assertEquals(1, waited.get(), "onWait runs once, before waiting")
        pool.shutdown()
    }

    @Test
    fun anInterruptedWaiterGivesUpItsPlaceInLine() {
        val slots = slots()
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        pool.submit { slots.withSlot(1) { holding.countDown(); release.await() } }
        holding.await()
        val interrupted = pool.submit { slots.withSlot(1) { } }
        Thread.sleep(100)
        interrupted.cancel(true)
        release.countDown()
        // Would hang if the cancelled request still stood first in line.
        pool.submit { slots.withSlot(1) { } }.get(5, TimeUnit.SECONDS)
        pool.shutdown()
    }

    @Test
    fun aFreedSlotGoesToTheTaskHoldingFewestEvenIfItAskedLater() {
        val slots = slots()
        // Task a holds both slots and asks for a third before task b asks for its first (#41).
        val a1 = slots.acquire(2, "a")
        val a2 = slots.acquire(2, "a")
        val pool = Executors.newFixedThreadPool(2)
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val aWaiting = CountDownLatch(1)
        val bWaiting = CountDownLatch(1)
        val a3 = pool.submit { slots.acquire(2, "a", onWait = { aWaiting.countDown() }).use { order += "a" } }
        aWaiting.await()
        val b1 = pool.submit { slots.acquire(2, "b", onWait = { bWaiting.countDown() }).use { order += "b"; Thread.sleep(50) } }
        bWaiting.await()
        Thread.sleep(50)
        assertTrue(a1.contended(), "b waits")
        a1.close()
        b1.get(5, TimeUnit.SECONDS)
        a2.close()
        a3.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("b", "a"), order)
        pool.shutdown()
    }

    @Test
    fun onlyAnotherTasksRequestContendsForASlot() {
        val slots = slots()
        val a1 = slots.acquire(1, "a")
        val pool = Executors.newFixedThreadPool(2)
        val waiting = CountDownLatch(1)
        val a2 = pool.submit { slots.acquire(1, "a", onWait = { waiting.countDown() }).close() }
        waiting.await()
        Thread.sleep(50)
        assertFalse(a1.contended(), "a's own second thread is no reason to give the slot up")
        val b = pool.submit { slots.acquire(1, "b").close() }
        Thread.sleep(50)
        assertTrue(a1.contended())
        a1.close()
        a1.close() // closing twice gives back one slot, not two
        a2.get(5, TimeUnit.SECONDS)
        b.get(5, TimeUnit.SECONDS)
        assertEquals(1, slots.peak)
        pool.shutdown()
    }

    @Test
    fun aTaskHoldingSeveralPassesOneToAWaitingTaskHoldingNone() {
        val slots = slots()
        val a1 = slots.acquire(2, "a")
        val a2 = slots.acquire(2, "a")
        assertFalse(a1.passToOwnerWithNone(), "nobody waits")
        val pool = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        val b = pool.submit<JvmSlots.Slot> { slots.acquire(2, "b", onWait = { waiting.countDown() }) }
        waiting.await()
        Thread.sleep(50)
        assertTrue(a1.passToOwnerWithNone())
        assertFalse(a2.passToOwnerWithNone(), "only one of a's slots goes")
        a1.close()
        val b1 = b.get(5, TimeUnit.SECONDS)
        assertFalse(a2.passToOwnerWithNone(), "a holds one slot, b holds one")
        assertFalse(b1.passToOwnerWithNone())
        a2.close()
        b1.close()
        assertEquals(2, slots.peak)
        pool.shutdown()
    }
}
