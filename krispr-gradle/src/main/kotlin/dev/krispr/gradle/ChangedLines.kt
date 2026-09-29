package dev.krispr.gradle

import org.gradle.api.GradleException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The lines diff mode mutates: those added or changed between the merge base of a ref and HEAD, and the
 * working tree, so uncommitted edits count. Untracked files count whole.
 *
 * Whitespace-only edits do not count (`--ignore-all-space`): re-indenting or reformatting changes no
 * behaviour, but in the diff-mode replay a formatter switch made every line of kotlinpoet "changed" and
 * reported 50 survivors in code nobody touched (docs/evidence.md). Whitespace inside a string literal is
 * behaviour, but krispr does not mutate string contents, so no mutant is lost with it.
 */
internal class ChangedLines(
    /** Canonical file to its changed line numbers (1-based). */
    private val lines: Map<File, Set<Int>>,
    /** Canonical untracked files, every line of which counts. */
    private val untracked: Set<File>,
    val mergeBase: String,
) {
    fun contains(file: File, line: Int): Boolean {
        val canonical = file.canonicalFile
        return canonical in untracked || lines[canonical]?.contains(line) == true
    }

    /** Every file with at least one changed or untracked line; deleted files are never in it. */
    val changedFiles: Set<File> get() = lines.keys + untracked

    companion object {
        fun since(ref: String, directory: File): ChangedLines {
            val root = File(git(directory, "rev-parse", "--show-toplevel").trim())
            val base = git(root, "merge-base", ref, "HEAD").trim()
            val diff = git(
                root, "-c", "core.quotePath=false", "diff", "--no-color", "--no-ext-diff", "--unified=0",
                "--ignore-all-space", "--find-renames", "--src-prefix=a/", "--dst-prefix=b/", base, "--",
            )
            val untracked = git(root, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "--full-name")
                .lines().filter { it.isNotBlank() }.mapTo(HashSet()) { File(root, it).canonicalFile }
            return ChangedLines(parse(diff, root), untracked, base)
        }

        /** Added and changed lines by file, from `git diff --unified=0` output. */
        internal fun parse(diff: String, root: File): Map<File, Set<Int>> {
            val changed = HashMap<File, MutableSet<Int>>()
            var current: MutableSet<Int>? = null
            val hunk = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")
            for (line in diff.lineSequence()) {
                when {
                    line.startsWith("+++ ") -> {
                        val path = line.removePrefix("+++ ")
                        current = if (path.startsWith("b/")) changed.getOrPut(File(root, path.removePrefix("b/")).canonicalFile) { HashSet() } else null
                    }
                    line.startsWith("@@ ") -> {
                        val match = hunk.find(line) ?: continue
                        val start = match.groupValues[1].toInt()
                        val count = match.groupValues[2].ifEmpty { "1" }.toInt()
                        current?.addAll(start until start + count)
                    }
                }
            }
            return changed
        }

        private fun git(directory: File, vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments).directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw GradleException("krispr: diff mode needs git: 'git ${arguments.joinToString(" ")}' failed in $directory: ${output.trim()}")
            }
            return output
        }
    }
}
