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
}
