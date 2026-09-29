package dev.krispr.playground.catalog

enum class Category { BOOKS, MUSIC, GAMES, TOOLS, GARDEN }

data class Product(
    val id: String,
    val name: String,
    val priceCents: Long,
    val stock: Int,
    val category: Category,
    val rating: Double = 0.0,
    val reviews: Int = 0,
    val tags: Set<String> = emptySet(),
    val ageDays: Int = 100,
    val salePriceCents: Long? = null,
) {
    val onSale: Boolean get() = salePriceCents != null && salePriceCents < priceCents
    val effectivePriceCents: Long get() = if (onSale) salePriceCents!! else priceCents
}
