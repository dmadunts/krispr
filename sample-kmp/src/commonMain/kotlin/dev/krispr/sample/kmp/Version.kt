package dev.krispr.sample.kmp

/** A semantic version, `major.minor.patch` with an optional `-pre` suffix. */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        if (major != other.major) return major - other.major
        if (minor != other.minor) return minor - other.minor
        if (patch != other.patch) return patch - other.patch
        // A pre-release sorts before its release.
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    /** Caret ranges: same major, at least this version; 0.x releases only match the same minor. */
    fun accepts(candidate: Version): Boolean {
        if (candidate < this) return false
        if (major == 0) return candidate.major == 0 && candidate.minor == minor
        return candidate.major == major
    }

    fun bump(part: Part): Version = when (part) {
        Part.MAJOR -> Version(major + 1, 0, 0)
        Part.MINOR -> Version(major, minor + 1, 0)
        Part.PATCH -> if (pre != null) copy(pre = null) else Version(major, minor, patch + 1)
    }

    override fun toString(): String = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    enum class Part { MAJOR, MINOR, PATCH }

    companion object {
        fun parse(text: String): Version? {
            val core = text.substringBefore('-')
            val pre = if ('-' in text) text.substringAfter('-').takeIf { it.isNotEmpty() } ?: return null else null
            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return Version(numbers[0], numbers[1], numbers[2], pre)
        }
    }
}

/** Where the running platform reads its version catalogue from; `actual` per JVM-hosted target. */
expect fun platformTag(): String
