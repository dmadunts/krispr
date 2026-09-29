package dev.krispr.playground.catalog

data class FilterSpec(
    val minCents: Long? = null,
    val maxCents: Long? = null,
    val categories: Set<Category> = emptySet(),
    val inStockOnly: Boolean = false,
    val minRating: Double = 0.0,
    val tag: String? = null,
    val sort: Sort = Sort.RELEVANCE,
)

enum class Sort { RELEVANCE, PRICE_LOW, PRICE_HIGH, RATING, NEWEST }

object CatalogFilter {
    fun apply(products: List<Product>, spec: FilterSpec): List<Product> {
        val kept = products.filter { matches(it, spec) }
        return when (spec.sort) {
            Sort.RELEVANCE -> kept
            Sort.PRICE_LOW -> kept.sortedBy { it.effectivePriceCents }
            Sort.PRICE_HIGH -> kept.sortedByDescending { it.effectivePriceCents }
            Sort.RATING -> kept.sortedWith(compareByDescending<Product> { it.rating }.thenByDescending { it.reviews })
            Sort.NEWEST -> kept.sortedBy { it.ageDays }
        }
    }

    fun matches(p: Product, spec: FilterSpec): Boolean {
        val price = p.effectivePriceCents
        if (spec.minCents != null && price < spec.minCents) return false
        if (spec.maxCents != null && price > spec.maxCents) return false
        if (spec.categories.isNotEmpty() && p.category !in spec.categories) return false
        if (spec.inStockOnly && p.stock <= 0) return false
        if (p.rating < spec.minRating) return false
        val tag = spec.tag
        if (tag != null && tag.isNotBlank() && p.tags.none { it.equals(tag, ignoreCase = true) }) return false
        return true
    }

    fun priceBounds(products: List<Product>): LongRange? {
        if (products.isEmpty()) return null
        var low = Long.MAX_VALUE
        var high = Long.MIN_VALUE
        for (p in products) {
            val price = p.effectivePriceCents
            if (price < low) low = price
            if (price > high) high = price
        }
        return low..high
    }

    fun countByCategory(products: List<Product>): Map<Category, Int> {
        val counts = mutableMapOf<Category, Int>()
        for (p in products) counts[p.category] = (counts[p.category] ?: 0) + 1
        return counts
    }
}
