package dev.krispr.playground.catalog

import android.content.Context

data class Row(val id: String, val title: String, val price: String, val stock: String, val badges: List<String>, val dimmed: Boolean)

data class Screen(val header: String, val rows: List<Row>, val pageLabel: String, val pages: List<Int>, val empty: Boolean)

class CatalogPresenter(context: Context, private val pageSize: Int = 4) {
    private val text = CatalogText(context)

    fun present(all: List<Product>, spec: FilterSpec, pageNumber: Int): Screen {
        val filtered = CatalogFilter.apply(all, spec)
        val page = Pagination.page(filtered, pageNumber, pageSize)
        val rows = page.items.map { p ->
            Row(
                id = p.id,
                title = if (p.name.length > MAX_TITLE) p.name.take(MAX_TITLE - 1) + "…" else p.name,
                price = text.price(p.effectivePriceCents),
                stock = text.stock(p),
                badges = text.badges(p),
                dimmed = p.stock <= 0,
            )
        }
        return Screen(
            header = text.results(filtered.size),
            rows = rows,
            pageLabel = text.pageOf(page),
            pages = Pagination.window(page.number, page.count),
            empty = filtered.isEmpty(),
        )
    }

    companion object {
        const val MAX_TITLE = 20
    }
}
