package dev.krispr.playground.checkout

import android.content.Context

class CheckoutText(private val context: Context) {
    fun delivery(days: Int): String =
        if (days <= 1) context.getString(R.string.delivery_tomorrow) else context.getString(R.string.delivery_days, days)

    fun card(number: String): String = context.getString(R.string.card_ending, CardValidator.digits(number).takeLast(4))
}
