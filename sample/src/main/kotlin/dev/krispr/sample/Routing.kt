package dev.krispr.sample

/** CONDITION_TRUE and CONDITION_FALSE: `if`, subjectless `when`, `while`, and `&&`/`||` clauses. */
object Routing {
    fun fee(total: Int, member: Boolean): Int {
        if (member && total >= 50) return 0
        return if (total >= 100) 0 else 5
    }

    fun zone(country: String, remote: Boolean): String = when {
        country == "NZ" -> "domestic"
        remote || country.isEmpty() -> "manual"
        else -> "international"
    }

    fun depot(code: String?): String = if (code != null && code.length == 3) code else "HUB"

    fun hops(distance: Int, range: Int): Int {
        var left = distance
        var count = 0
        while (left > 0) {
            left -= range
            count++
        }
        return count
    }

    fun retries(limit: Int): Int {
        var attempt = 0
        do attempt++ while (attempt < limit)
        return attempt
    }
}
