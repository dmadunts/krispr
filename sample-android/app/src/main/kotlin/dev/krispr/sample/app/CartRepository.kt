package dev.krispr.sample.app

import dev.krispr.sample.lib.Cart
import dev.krispr.sample.lib.CartLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CartRepository @Inject constructor() {
    private val state = MutableStateFlow(Cart(emptyList()))
    val cart: StateFlow<Cart> = state

    fun add(line: CartLine) = state.update { cart ->
        val existing = cart.lines.indexOfFirst { it.sku == line.sku }
        if (existing < 0) cart.copy(lines = cart.lines + line)
        else cart.copy(lines = cart.lines.mapIndexed { i, l -> if (i == existing) l.copy(quantity = l.quantity + line.quantity) else l })
    }

    fun applyCoupon(percent: Int) = state.update { it.copy(couponPercent = percent) }
}
