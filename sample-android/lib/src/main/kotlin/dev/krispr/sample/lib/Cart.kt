package dev.krispr.sample.lib

import android.os.Parcelable
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize

/** Parcelize writes writeToParcel/CREATOR; Moshi's KSP processor writes CartLineJsonAdapter. */
@Parcelize
@JsonClass(generateAdapter = true)
data class CartLine(val sku: String, val quantity: Int, val unitCents: Long) : Parcelable

@JsonClass(generateAdapter = true)
data class Cart(val lines: List<CartLine>, val couponPercent: Int = 0)

object CartCalculator {
    /** Orders at or above this subtotal ship free. */
    const val FREE_SHIPPING_CENTS = 5_000L
    const val SHIPPING_CENTS = 499L

    fun subtotal(cart: Cart): Long = cart.lines.sumOf { it.quantity * it.unitCents }

    fun discount(cart: Cart): Long {
        val percent = cart.couponPercent.coerceIn(0, 50)
        return subtotal(cart) * percent / 100
    }

    fun shipping(cart: Cart): Long =
        if (subtotal(cart) - discount(cart) >= FREE_SHIPPING_CENTS || cart.lines.isEmpty()) 0 else SHIPPING_CENTS

    fun total(cart: Cart): Long = subtotal(cart) - discount(cart) + shipping(cart)

    fun itemCount(cart: Cart): Int = cart.lines.sumOf { it.quantity }
}
