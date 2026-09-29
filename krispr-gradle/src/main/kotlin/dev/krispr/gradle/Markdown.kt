package dev.krispr.gradle

/**
 * A CommonMark code span whose fence is longer than any backtick run the text contains, so file names,
 * source snippets and test names with backticks (or `|`, `*`, `_`, `[x](y)` — all inert once inside a code
 * span) can never break out of it or inject markdown. Newlines are flattened first: a blank line or list
 * marker inside the content would otherwise end the list item at the block level before the inline code
 * span parser ever sees the closing fence.
 */
internal fun codeSpan(text: String): String {
    val flat = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    val maxRun = Regex("`+").findAll(flat).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxRun + 1)
    val body = if (flat.startsWith("`") || flat.endsWith("`")) " $flat " else flat
    return "$fence$body$fence"
}
