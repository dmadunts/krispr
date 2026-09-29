package dev.krispr.playground.session

data class Session(val number: Int, val user: String, val startedAt: Long, val expiresAt: Long)

/** Process-wide session registry: numbers sessions from 1 for the life of the process. */
object SessionManager {
    private var next = 1
    private val open = LinkedHashMap<String, Session>()

    fun start(user: String): Session {
        val graph = AppGraph.current
        val now = graph.clock()
        open[user]?.let { if (it.expiresAt > now) return it }
        if (open.size >= graph.maxSessions) {
            val oldest = open.values.minByOrNull { it.startedAt }!!
            open.remove(oldest.user)
        }
        val session = Session(next++, user, now, now + graph.tokenTtlMillis)
        open[user] = session
        return session
    }

    fun isActive(user: String): Boolean {
        val s = open[user] ?: return false
        return s.expiresAt > AppGraph.current.clock()
    }

    fun end(user: String): Boolean = open.remove(user) != null

    fun activeCount(): Int {
        val now = AppGraph.current.clock()
        return open.values.count { it.expiresAt > now }
    }
}
