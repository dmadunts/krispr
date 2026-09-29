package dev.krispr.playground.catalog

class SearchIndex(products: List<Product>) {
    private val byToken = HashMap<String, MutableSet<Product>>()
    private val all = products

    init {
        for (p in products) {
            for (token in tokenize(p.name)) byToken.getOrPut(token) { linkedSetOf() }.add(p)
            for (tag in p.tags) byToken.getOrPut(tag.lowercase()) { linkedSetOf() }.add(p)
        }
    }

    fun search(query: String, limit: Int = 10): List<Product> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val scores = HashMap<Product, Int>()
        for (token in tokens) {
            byToken[token]?.forEach { scores[it] = (scores[it] ?: 0) + 2 }
            if (token.length >= 3) {
                for (p in all) if (p.name.lowercase().contains(token) && byToken[token]?.contains(p) != true) {
                    scores[p] = (scores[p] ?: 0) + 1
                }
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Product, Int>> { it.value }.thenBy { it.key.name })
            .take(limit)
            .map { it.key }
    }

    companion object {
        private val stopWords = setOf("the", "a", "an", "of", "and")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(' ', '-', ',', '.')
                .filter { it.length > 1 && it !in stopWords }
    }
}
