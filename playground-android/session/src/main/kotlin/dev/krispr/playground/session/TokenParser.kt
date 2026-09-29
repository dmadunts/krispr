package dev.krispr.playground.session

data class Token(val user: String, val issuedAt: Long, val scopes: Set<String>)

object TokenParser {
    fun parse(raw: String): Token? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val issued = parts[1].toLongOrNull() ?: return null
        if (issued < 0) return null
        val user = parts[0].takeIf { it.isNotBlank() } ?: return null
        val scopes = if (parts[2].isEmpty()) emptySet() else parts[2].split(',').toSet()
        return Token(user, issued, scopes)
    }

    fun expired(t: Token, now: Long, ttl: Long): Boolean = now - t.issuedAt > ttl
}
