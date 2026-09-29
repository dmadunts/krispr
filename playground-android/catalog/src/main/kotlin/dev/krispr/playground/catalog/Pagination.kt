package dev.krispr.playground.catalog

data class Page<T>(val items: List<T>, val number: Int, val count: Int) {
    val hasNext: Boolean get() = number < count
    val hasPrevious: Boolean get() = number > 1
}

object Pagination {
    fun pageCount(total: Int, pageSize: Int): Int {
        require(pageSize > 0)
        if (total <= 0) return 1
        return (total + pageSize - 1) / pageSize
    }

    fun <T> page(items: List<T>, number: Int, pageSize: Int): Page<T> {
        val count = pageCount(items.size, pageSize)
        val safe = number.coerceIn(1, count)
        val from = (safe - 1) * pageSize
        val to = minOf(from + pageSize, items.size)
        return Page(if (from < to) items.subList(from, to) else emptyList(), safe, count)
    }

    fun window(current: Int, count: Int, width: Int = 5): List<Int> {
        if (count <= width) return (1..count).toList()
        var start = current - width / 2
        if (start < 1) start = 1
        var end = start + width - 1
        if (end > count) {
            end = count
            start = end - width + 1
        }
        return (start..end).toList()
    }
}
