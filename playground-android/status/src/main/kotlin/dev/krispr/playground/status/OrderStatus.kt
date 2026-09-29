package dev.krispr.playground.status

enum class OrderStatus { PLACED, PAID, PACKED, SHIPPED, DELIVERED, CANCELLED, REFUNDED }

sealed interface Event {
    data class Paid(val cents: Long) : Event
    data object Packed : Event
    data class Shipped(val carrier: String) : Event
    data object Delivered : Event
    data class Cancelled(val reason: String) : Event
}

/** Exhaustive whens with no else branch: the #35 shape, where an instrumented when must not reach its throw. */
object OrderFlow {
    fun label(s: OrderStatus): String = when (s) {
        OrderStatus.PLACED -> "Order placed"
        OrderStatus.PAID -> "Payment received"
        OrderStatus.PACKED -> "Packed"
        OrderStatus.SHIPPED -> "On its way"
        OrderStatus.DELIVERED -> "Delivered"
        OrderStatus.CANCELLED -> "Cancelled"
        OrderStatus.REFUNDED -> "Refunded"
    }

    fun isTerminal(s: OrderStatus): Boolean = when (s) {
        OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REFUNDED -> true
        OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.PACKED, OrderStatus.SHIPPED -> false
    }

    fun progress(s: OrderStatus): Int {
        val step = when (s) {
            OrderStatus.PLACED -> 1
            OrderStatus.PAID -> 2
            OrderStatus.PACKED -> 3
            OrderStatus.SHIPPED -> 4
            OrderStatus.DELIVERED -> 5
            OrderStatus.CANCELLED, OrderStatus.REFUNDED -> 0
        }
        return step * 100 / 5
    }

    fun apply(s: OrderStatus, e: Event): OrderStatus {
        if (isTerminal(s)) return s
        return when (e) {
            is Event.Paid -> if (s == OrderStatus.PLACED && e.cents > 0) OrderStatus.PAID else s
            Event.Packed -> if (s == OrderStatus.PAID) OrderStatus.PACKED else s
            is Event.Shipped -> if (s == OrderStatus.PACKED && e.carrier.isNotBlank()) OrderStatus.SHIPPED else s
            Event.Delivered -> if (s == OrderStatus.SHIPPED) OrderStatus.DELIVERED else s
            is Event.Cancelled -> if (s == OrderStatus.SHIPPED) s else OrderStatus.CANCELLED
        }
    }

    fun describe(e: Event): String = when (val captured = e) {
        is Event.Paid -> "paid ${captured.cents}"
        Event.Packed -> "packed"
        is Event.Shipped -> "shipped by ${captured.carrier}"
        Event.Delivered -> "delivered"
        is Event.Cancelled -> "cancelled: ${captured.reason}"
    }
}
