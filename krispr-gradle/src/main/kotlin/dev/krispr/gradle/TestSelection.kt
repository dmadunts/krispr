package dev.krispr.gradle

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A recorded test: its selector (JUnit unique id), class, method (empty for a class-level selector),
 * duration, and [ownMillis], the duration without framework set-up such as creating Robolectric's sandbox,
 * which only the first test in a sandbox pays. Class set-up is outside both.
 */
internal class RecordedTest(
    val selector: String,
    val className: String,
    val methodName: String,
    val millis: Long,
    val ownMillis: Long = millis,
    /** Whether a test framework (Robolectric) reported while it ran; null when the recording predates the column. */
    val framework: Boolean? = null,
) {
    /** Its own time is over [thresholdMillis]. */
    fun isSlow(thresholdMillis: Long): Boolean = ownMillis > thresholdMillis
}

/**
 * Which recorded tests may kill mutants. Screenshot tests fail on any pixel change, so they would "kill"
 * nearly every mutant in UI code without saying anything about behaviour, and they are the slowest
 * tests; they are left out unless `useScreenshotTests` is set. `quarantinedTests` (known-flaky tests)
 * and `excludeTests` patterns leave out more, and so does [slowThresholdMillis] unless it is null.
 */
internal class TestSelection(
    private val screenshotClasses: Map<String, String>,
    excludePatterns: List<String>,
    quarantinePatterns: List<String> = emptyList(),
    private val slowThresholdMillis: Long? = null,
) {
    private val excluded = excludePatterns.map { it to patternRegex(it) }
    private val quarantined = quarantinePatterns.map { it to patternRegex(it) }

    /**
     * Why [test] may not kill mutants, or null when it may. Only a [leaf] test (one with no recorded tests
     * inside it) is judged by its duration: a class's duration is the sum of its tests.
     */
    fun exclusionReason(test: RecordedTest, leaf: Boolean = true): String? {
        val topLevel = test.className.substringBefore('$')
        screenshotClasses[topLevel]?.let { return "screenshot test ($it)" }
        val names = listOfNotNull(test.className, test.methodName.takeIf { it.isNotEmpty() }?.let { "${test.className}.$it" })
        fun List<Pair<String, Regex>>.match() = firstOrNull { (_, regex) -> names.any { regex.matches(it) } }?.first
        quarantined.match()?.let { return "quarantinedTests '$it'" }
        excluded.match()?.let { return "excludeTests '$it'" }
        return if (slowThresholdMillis != null && leaf && test.isSlow(slowThresholdMillis)) "slow test (${test.ownMillis} ms)" else null
    }

    companion object {
        /**
         * Gradle's `--tests` syntax: `*` wildcards over `pkg.Class` or `pkg.Class.method`, and a pattern
         * that starts with an uppercase letter matches the simple class name.
         */
        internal fun patternRegex(pattern: String): Regex {
            val body = pattern.split('*').joinToString(".*") { Regex.escape(it) }
            val prefix = if (pattern.firstOrNull()?.isUpperCase() == true) "(.*\\.)?" else ""
            return Regex("$prefix$body(\\$.*)?")
        }
    }
}

/**
 * Finds the classes that use a screenshot testing library directly. It reads the constant pool of every
 * class in the project's own classpath entries (the test classes, other modules of the build), so a test
 * counts when it calls a capture API (Roborazzi's `captureRoboImage`), holds a rule (Paparazzi,
 * Dropshots), implements an interface (Shot) or names a runner or annotation from one of the libraries.
 * A test that only calls a helper that does (nowinandroid's `captureMultiTheme`) does not count: a shared
 * test helper would otherwise take every test that uses it out, screenshot or not. Such a test is a
 * killer like any other; list it in `excludeTests` to leave it out.
 */
internal object ScreenshotTests {
    /** Internal-name package prefixes of screenshot and snapshot testing libraries, and their names. */
    val LIBRARIES = linkedMapOf(
        "com/github/takahirom/roborazzi/" to "Roborazzi",
        "app/cash/paparazzi/" to "Paparazzi",
        "com/karumi/shot/" to "Shot",
        "com/dropbox/dropshots/" to "Dropshots",
        "com/facebook/testing/screenshot/" to "screenshot-tests-for-android",
        "com/android/tools/screenshot/" to "Compose Preview Screenshot Testing",
        "com/emergetools/snapshots/" to "Emerge snapshots",
        "dev/testify/" to "Testify",
    )

    /** Top-level class name (dotted) to the screenshot library it uses, for each class in [roots] that uses one. */
    fun detect(roots: Collection<File>): Map<String, String> {
        // Nested and synthetic classes (lambdas, ComposableSingletons) count as their top-level class.
        val library = HashMap<String, String>()
        for (root in roots) {
            forEachClass(root) { stream ->
                val pool = ConstantPool.read(stream) ?: return@forEachClass
                val owner = topLevel(pool.thisClass ?: return@forEachClass)
                if (owner !in library) {
                    pool.strings.firstNotNullOfOrNull { text -> LIBRARIES.entries.firstOrNull { text.contains(it.key) }?.value }
                        ?.let { library[owner] = it }
                }
            }
        }
        return library.mapKeys { it.key.replace('/', '.') }
    }

    private fun topLevel(internalName: String) = internalName.substringBefore('$')

    private fun forEachClass(root: File, action: (InputStream) -> Unit) {
        when {
            root.isDirectory -> root.walk().filter { it.isFile && it.name.endsWith(".class") }.forEach { file -> file.inputStream().buffered().use(action) }
            root.isFile && root.name.endsWith(".jar") -> ZipFile(root).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry -> zip.getInputStream(entry).buffered().use(action) }
            }
        }
    }

    /** The parts of a class file's constant pool this needs: its own name, the classes it names, and every string. */
    private class ConstantPool(val thisClass: String?, val classes: List<String>, val strings: List<String>) {
        companion object {
            fun read(stream: InputStream): ConstantPool? {
                val input = DataInputStream(stream)
                if (input.readInt() != 0xCAFEBABE.toInt()) return null
                input.readUnsignedShort()
                input.readUnsignedShort()
                val count = input.readUnsignedShort()
                val utf8 = arrayOfNulls<String>(count)
                val classIndexes = IntArray(count) { -1 }
                var i = 1
                while (i < count) {
                    when (input.readUnsignedByte()) {
                        1 -> utf8[i] = input.readUTF()
                        7 -> classIndexes[i] = input.readUnsignedShort()
                        8, 16, 19, 20 -> input.readUnsignedShort()
                        15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                        3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                        5, 6 -> { input.readLong(); i++ }
                        else -> return null
                    }
                    i++
                }
                input.readUnsignedShort() // access flags
                val thisIndex = input.readUnsignedShort()
                val classes = classIndexes.filter { it > 0 }.mapNotNull { utf8.getOrNull(it) }
                return ConstantPool(utf8.getOrNull(classIndexes.getOrElse(thisIndex) { -1 }), classes, utf8.filterNotNull())
            }
        }
    }
}
