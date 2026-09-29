package dev.krispr.gradle

import java.io.File

/**
 * Rules from `.krispr-exclude` files, one per line: whitespace-separated `key=glob` terms that must all
 * match, `#` starting a comment. A bare term is a `file=` glob.
 *
 * - `file`: the source path relative to the module or the build root, or its name when the glob has no `/`.
 *   `**` crosses directories, `*` and `?` do not.
 * - `class`: the declaring class or, for top-level code, the package, fully qualified or by simple name.
 * - `function`: the function or property name (`<init>` for constructors, `<init-block>` for `init`).
 * - `operator`: the operator, such as `MATH` or `NEGATE_IF`, in any case.
 * - `lines`: `12`, `10-20`, or a comma-separated list of either.
 *
 * In `class`, `function` and `operator` globs, `*` matches anything, dots included.
 */
internal class MutantExclusions(private val rules: List<Rule>) {

    class Rule(val text: String, private val terms: List<Pair<String, String>>) {
        fun matches(mutant: Candidate, roots: List<File>): Boolean = terms.all { (key, value) ->
            when (key) {
                "file" -> matchesFile(value, File(mutant.file), roots)
                "class" -> owner(mutant.declaration).let { it != null && (glob(value, it) || glob(value, it.substringAfterLast('.'))) }
                "function" -> member(mutant.declaration).let { it != null && glob(value, it) }
                "operator" -> glob(value.uppercase(), mutant.operator.uppercase())
                "lines" -> value.split(',').any { range ->
                    val (from, to) = range.split('-').map { it.trim().toInt() }.let { it.first() to it.last() }
                    mutant.line in from..to
                }
                else -> false
            }
        }
    }

    /** What a rule sees of a mutant. */
    class Candidate(val file: String, val line: Int, val operator: String, val declaration: String?)

    fun excludes(mutant: Candidate, roots: List<File>): Rule? = rules.firstOrNull { it.matches(mutant, roots) }

    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        const val FILE_NAME = ".krispr-exclude"
        private val KEYS = setOf("file", "class", "function", "operator", "lines")

        /** The rules of every `.krispr-exclude` among [directories]; a missing file has none. */
        fun read(directories: Collection<File>): MutantExclusions =
            MutantExclusions(directories.distinct().map { File(it, FILE_NAME) }.filter { it.isFile }.flatMap { parse(it.readText(), it.path) })

        fun parse(text: String, source: String = FILE_NAME): List<Rule> = text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val terms = line.split(Regex("\\s+")).map { term ->
                val key = if ('=' in term) term.substringBefore('=') else "file"
                val value = term.substringAfter('=')
                require(key in KEYS && value.isNotEmpty()) {
                    "$source:${index + 1}: '$term' is not one of ${KEYS.joinToString("=, ")}=<glob>"
                }
                if (key == "lines") require(value.matches(Regex("""\d+(-\d+)?(,\d+(-\d+)?)*"""))) {
                    "$source:${index + 1}: lines=$value is not like 12, 10-20 or 3,10-20"
                }
                key to value
            }
            Rule(line, terms)
        }

        /** `com.example.Cart.total(kotlin.Int)` → `com.example.Cart`. */
        internal fun owner(declaration: String?): String? =
            declaration?.substringBefore('(')?.takeIf { '.' in it }?.substringBeforeLast('.')

        /** `com.example.Cart.total(kotlin.Int)` → `total`. */
        internal fun member(declaration: String?): String? = declaration?.substringBefore('(')?.substringAfterLast('.')

        private fun matchesFile(pattern: String, file: File, roots: List<File>): Boolean {
            if ('/' !in pattern) return pathGlob(pattern, file.name)
            return roots.any { root ->
                val relative = file.absoluteFile.relativeToOrNull(root.absoluteFile)?.invariantSeparatorsPath
                relative != null && !relative.startsWith("..") && pathGlob(pattern.removePrefix("/"), relative)
            }
        }

        private fun pathGlob(pattern: String, path: String): Boolean = regex(pattern, separator = true).matches(path)

        private fun glob(pattern: String, value: String): Boolean = regex(pattern, separator = false).matches(value)

        private fun regex(pattern: String, separator: Boolean): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    // `**/` also matches no directory at all.
                    separator && pattern.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 2 }
                    separator && pattern.startsWith("**", i) -> { out.append(".*"); i++ }
                    c == '*' -> out.append(if (separator) "[^/]*" else ".*")
                    c == '?' -> out.append(if (separator) "[^/]" else ".")
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(out.toString())
        }
    }
}
