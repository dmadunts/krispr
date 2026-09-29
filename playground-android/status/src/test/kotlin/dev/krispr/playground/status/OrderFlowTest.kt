package dev.krispr.playground.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderFlowTest {
    @Test
    fun labelsEveryStatus() {
        assertEquals(
            listOf("Order placed", "Payment received", "Packed", "On its way", "Delivered", "Cancelled", "Refunded"),
            OrderStatus.entries.map(OrderFlow::label),
        )
    }

    @Test
    fun terminalAndProgress() {
        assertTrue(OrderFlow.isTerminal(OrderStatus.REFUNDED))
        assertFalse(OrderFlow.isTerminal(OrderStatus.SHIPPED))
        assertEquals(listOf(20, 40, 60, 80, 100, 0, 0), OrderStatus.entries.map(OrderFlow::progress))
    }

    @Test
    fun happyPath() {
        val events = listOf(Event.Paid(100), Event.Packed, Event.Shipped("DHL"), Event.Delivered)
        assertEquals(OrderStatus.DELIVERED, events.fold(OrderStatus.PLACED, OrderFlow::apply))
    }

    @Test
    fun rejectedTransitions() {
        assertEquals(OrderStatus.PLACED, OrderFlow.apply(OrderStatus.PLACED, Event.Paid(0)))
        assertEquals(OrderStatus.PLACED, OrderFlow.apply(OrderStatus.PLACED, Event.Packed))
        assertEquals(OrderStatus.PACKED, OrderFlow.apply(OrderStatus.PACKED, Event.Shipped(" ")))
        assertEquals(OrderStatus.SHIPPED, OrderFlow.apply(OrderStatus.SHIPPED, Event.Cancelled("late")))
        assertEquals(OrderStatus.CANCELLED, OrderFlow.apply(OrderStatus.PAID, Event.Cancelled("changed mind")))
        assertEquals(OrderStatus.DELIVERED, OrderFlow.apply(OrderStatus.DELIVERED, Event.Cancelled("x")))
    }

    @Test
    fun describes() {
        assertEquals("shipped by DHL", OrderFlow.describe(Event.Shipped("DHL")))
        assertEquals("cancelled: x", OrderFlow.describe(Event.Cancelled("x")))
        assertEquals("packed", OrderFlow.describe(Event.Packed))
    }
}
