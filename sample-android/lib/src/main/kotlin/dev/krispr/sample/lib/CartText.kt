package dev.krispr.sample.lib

import android.content.Context

/** Resource-backed text: exercised under Robolectric, which resolves R.string through AGP's test config. */
class CartText(private val context: Context) {
    fun summary(cart: Cart): String {
        val count = CartCalculator.itemCount(cart)
        return if (count == 0) context.getString(R.string.cart_empty)
        else context.getString(R.string.items_in_cart, count)
    }

    fun isDebugBuild(): Boolean = BuildConfig.DEBUG
}
