package dev.krispr.sample.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.krispr.sample.lib.CartCalculator
import dev.krispr.sample.lib.CartLine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CartUiState(val items: Int = 0, val totalCents: Long = 0, val canCheckout: Boolean = false)

@HiltViewModel
class CartViewModel @Inject constructor(private val repository: CartRepository) : ViewModel() {
    val state: StateFlow<CartUiState> = repository.cart
        .map { cart ->
            val items = CartCalculator.itemCount(cart)
            CartUiState(items, CartCalculator.total(cart), canCheckout = items > 0 && items <= MAX_ITEMS)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CartUiState())

    fun add(sku: String, unitCents: Long, quantity: Int = 1) {
        if (quantity <= 0) return
        repository.add(CartLine(sku, quantity, unitCents))
    }

    /** Codes are `SAVE<n>`; anything else is ignored. */
    fun redeem(code: String) {
        val percent = code.removePrefix("SAVE").toIntOrNull() ?: return
        if (code.startsWith("SAVE") && percent > 0) repository.applyCoupon(percent)
    }

    private companion object {
        const val MAX_ITEMS = 20
    }
}
