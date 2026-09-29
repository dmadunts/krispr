package dev.krispr.compiler

/**
 * Keeps a mutant's description (`before → after`) short enough for a terminal line or a PR comment
 * without losing the change. Each side may run to [limit] characters. A longer side is not cut at the
 * end: that hid the mutant whenever it sat deep in a long expression, since both sides then showed
 * the same first characters (clikt's `Option.kt:178` in docs/evidence.md read the same before and
 * after). Instead the text the two sides share at the start and at the end is folded into `…`, keeping
 * [context] characters of it on each side of the change, so what differs is always in view. A side that is still too long after that (a change to something very long in
 * itself) is cut at the end as a last resort.
 */
fun fitDescription(description: String, limit: Int = 100, context: Int = 24): String {
    val arrow = " → "
    val at = description.indexOf(arrow)
    if (at < 0) return cut(description, limit)
    var before = description.substring(0, at)
    var after = description.substring(at + arrow.length)
    if (before.length <= limit && after.length <= limit) return description

    // Fold the shared prefix, keeping `context` characters of it before the change.
    val prefix = before.commonPrefixWith(after).length
    if (prefix > context + 1) {
        val keepFrom = prefix - context
        before = "…" + before.substring(keepFrom)
        after = "…" + after.substring(keepFrom)
    }
    // Fold the shared suffix, keeping `context` characters of it after the change. Never let the suffix
    // overlap the prefix that was kept, so a replacement inside a repeated pattern is not folded away.
    val suffix = minOf(before.commonSuffixWith(after).length, before.length - 1, after.length - 1)
    if (suffix > context + 1) {
        before = before.dropLast(suffix - context) + "…"
        after = after.dropLast(suffix - context) + "…"
    }
    return cut(before, limit) + arrow + cut(after, limit)
}

private fun cut(text: String, limit: Int): String = if (text.length > limit) text.take(limit - 3) + "..." else text
