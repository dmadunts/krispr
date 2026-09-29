package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChangedLinesTest {
    @Test
    fun parsesAddedAndChangedLinesPerFile(@TempDir root: File) {
        val diff = """
            diff --git a/src/A.kt b/src/A.kt
            index 1..2 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -3 +3 @@ fun a() {
            -    return 1
            +    return 2
            @@ -10,2 +11,3 @@
            +x
            +y
            +z
            @@ -20,4 +23,0 @@
            diff --git a/src/Gone.kt b/src/Gone.kt
            deleted file mode 100644
            --- a/src/Gone.kt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            diff --git a/New.kt b/New.kt
            new file mode 100644
            --- /dev/null
            +++ b/New.kt
            @@ -0,0 +1,2 @@
        """.trimIndent()

        val changed = ChangedLines.parse(diff, root)

        assertEquals(setOf(3, 11, 12, 13), changed[File(root, "src/A.kt").canonicalFile])
        assertEquals(setOf(1, 2), changed[File(root, "New.kt").canonicalFile])
        assertEquals(2, changed.size)
    }

    @Test
    fun whitespaceOnlyEditsAreNotChanges(@TempDir root: File) {
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
                .directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }
        val source = File(root, "A.kt")
        source.writeText("fun a(x: Int): Int {\n    if (x > 0) return 1\n    return 2\n}\n")
        git("init", "-q")
        git("add", ".")
        git("commit", "-q", "-m", "base")
        // Re-indented throughout, and one real change on line 3.
        source.writeText("fun a(x: Int): Int {\n  if (x > 0) return 1\n  return 3\n}\n")

        val changed = ChangedLines.since("HEAD", root)

        assertEquals(setOf(source.canonicalFile), changed.changedFiles)
        assertEquals(listOf(3), (1..4).filter { changed.contains(source, it) })
    }
}
