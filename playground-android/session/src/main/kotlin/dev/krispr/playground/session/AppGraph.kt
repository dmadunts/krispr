package dev.krispr.playground.session

/** A hand-rolled DI holder, installed once per process like a Koin or Dagger application graph. */
object AppGraph {
    private var graph: Graph? = null

    val current: Graph get() = graph ?: error("AppGraph is not installed")

    fun install(g: Graph) {
        check(graph == null) { "AppGraph is already installed" }
        graph = g
    }
}

class Graph(val clock: () -> Long, val maxSessions: Int, val tokenTtlMillis: Long)
