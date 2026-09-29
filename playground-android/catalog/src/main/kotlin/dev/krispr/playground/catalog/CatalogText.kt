package dev.krispr.playground.catalog

import android.content.Context

/** Display strings from resources: the reason this module's tests need Robolectric. */
class CatalogText(private val context: Context) {
    fun stock(p: Product): String = when {
        p.stock <= 0 -> context.getString(R.string.out_of_stock)
        p.stock < LOW_STOCK -> context.getString(R.string.low_stock, p.stock)
        else -> context.getString(R.string.in_stock)
    }

    fun results(count: Int): String = context.resources.getQuantityString(R.plurals.results, count, count)

    fun pageOf(page: Page<*>): String = context.getString(R.string.page_of, page.number, page.count)

    fun rating(p: Product): String {
        if (p.reviews == 0) return context.getString(R.string.no_rating)
        val rounded = Math.round(p.rating * 10) / 10.0
        return context.getString(R.string.rating, rounded.toString(), p.reviews)
    }

    fun badges(p: Product): List<String> {
        val out = mutableListOf<String>()
        if (p.onSale) out += context.getString(R.string.sale)
        if (p.ageDays < NEW_DAYS) out += context.getString(R.string.new_arrival)
        return out
    }

    fun price(cents: Long): String {
        val whole = cents / 100
        val frac = cents % 100
        return "£" + whole + "." + (if (frac < 10) "0" else "") + frac
    }

    fun priceFrom(products: List<Product>): String? {
        val bounds = CatalogFilter.priceBounds(products) ?: return null
        return context.getString(R.string.price_from, price(bounds.first))
    }

    companion object {
        const val LOW_STOCK = 5
        const val NEW_DAYS = 30
    }
}
