package dev.krispr.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * The value probe behind `showChanges`: with `-Dkrispr.probe=N`, every evaluation of mutant N's site
 * (the mutated expression's result, the condition that picks a branch, or the field an assignment stores
 * to) is rendered and appended to `-Dkrispr.probeOut`, so the Gradle task can compare the values seen
 * with the mutant off and on. The compiler plugin only emits the probe when asked to, and it does
 * nothing unless [Mutants.isProbed] matches.
 *
 * Lines are `<kind><TAB><text>`: `SV`/`SO` for each of the first [SEQUENCE] evaluations in order, `DV`/`DO`
 * for each of the first [DISTINCT] distinct values, and `M` once when there were more distinct values.
 * `V` is a rendered value, `O` an opaque one whose type is all that was recorded. A copy of this class in
 * a Robolectric sandbox appends to the same file; the reader drops the duplicates.
 *
 * Rendering never calls a getter or anything else that could change state: nulls, primitives, strings,
 * enums, `Unit`, data classes (their generated `toString`), and the JDK's and Kotlin's own collections
 * and arrays of those are rendered; anything else is recorded as `<TypeName>`. Each value is cut at
 * [MAX_CHARS], and `toString` runs inside a try/catch.
 */
object Probe {
    const val PROBE_PROPERTY = "krispr.probe"
    const val OUT_PROPERTY = "krispr.probeOut"
    const val SEQUENCE = 20
    const val DISTINCT = 3
    const val MAX_CHARS = 80

    /** Stops rendering after this many evaluations of a hot site whose values are all known already. */
    private const val MAX_RENDERS = 10_000

    /** Written for a store that the mutant skipped; see [Mutants.observeSkipped]. */
    const val SKIPPED = "(skipped)"

    /** The file observations are appended to; null writes nothing. Writable for in-process tests. */
    @JvmStatic
    var out: File? = System.getProperty(OUT_PROPERTY)?.let(::File)

    private var renders = 0
    private var sequence = 0
    private val distinct = LinkedHashSet<String>()
    private var more = false

    /** Forgets what was seen, for in-process tests. */
    @JvmStatic
    @Synchronized
    fun reset() {
        renders = 0
        sequence = 0
        distinct.clear()
        more = false
    }

    @JvmStatic
    @Synchronized
    fun observe(value: Any?) {
        if (renders >= MAX_RENDERS || (sequence >= SEQUENCE && more)) return
        renders++
        val (text, opaque) = try {
            render(value)
        } catch (e: Throwable) {
            "<${typeName(value)}>" to true
        }
        record(text, opaque)
    }

    @JvmStatic
    @Synchronized
    fun observeSkipped() = record(SKIPPED, false)

    private fun record(text: String, opaque: Boolean) {
        val kind = if (opaque) "O" else "V"
        val lines = ArrayList<String>(3)
        if (sequence < SEQUENCE) {
            sequence++
            lines += "S$kind\t$text"
        }
        if (text !in distinct) {
            if (distinct.size < DISTINCT) {
                distinct += text
                lines += "D$kind\t$text"
            } else if (!more) {
                more = true
                lines += "M\t"
            }
        }
        if (lines.isNotEmpty()) write(lines)
    }

    private fun write(lines: List<String>) {
        val file = out ?: return
        try {
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).use { writer ->
                lines.forEach { writer.write(it); writer.write("\n") }
            }
        } catch (e: Exception) {
            // A probe must never fail a test.
        }
    }

    /** The value's text and whether it is opaque (only its type is known). */
    internal fun render(value: Any?): Pair<String, Boolean> {
        val builder = StringBuilder()
        val opaque = append(builder, value, depth = 0)
        return cap(builder.toString()) to opaque
    }

    /** Appends [value]'s rendering; true when all it could append was a type name. */
    private fun append(builder: StringBuilder, value: Any?, depth: Int): Boolean {
        when {
            value == null -> builder.append("null")
            value is String -> quote(builder, value)
            value is Char -> builder.append('\'').append(escape(value.toString())).append('\'')
            value is Boolean || value is Unit || isJdkNumber(value) || isUnsigned(value) -> builder.append(value.toString())
            value is Enum<*> -> builder.append(safeToString(value) ?: value.name)
            depth < MAX_DEPTH && isStandardCollection(value) -> elements(builder, (value as Iterable<*>).iterator(), "[", "]", depth)
            depth < MAX_DEPTH && isStandardMap(value) -> {
                builder.append('{')
                var first = true
                for ((key, element) in (value as Map<*, *>).entries) {
                    if (builder.length > MAX_CHARS) break
                    if (!first) builder.append(", ")
                    first = false
                    append(builder, key, depth + 1)
                    builder.append('=')
                    append(builder, element, depth + 1)
                }
                builder.append('}')
            }
            depth < MAX_DEPTH && value.javaClass.isArray -> elements(builder, arrayIterator(value), "[", "]", depth)
            isDataClass(value) -> {
                val text = safeToString(value) ?: return opaque(builder, value)
                builder.append(text)
            }
            else -> return opaque(builder, value)
        }
        return false
    }

    private fun opaque(builder: StringBuilder, value: Any): Boolean {
        builder.append('<').append(typeName(value)).append('>')
        return true
    }

    private fun elements(builder: StringBuilder, iterator: Iterator<*>, open: String, close: String, depth: Int) {
        builder.append(open)
        var first = true
        while (iterator.hasNext()) {
            if (builder.length > MAX_CHARS) break
            if (!first) builder.append(", ")
            first = false
            append(builder, iterator.next(), depth + 1)
        }
        builder.append(close)
    }

    private fun arrayIterator(array: Any): Iterator<Any?> {
        val size = java.lang.reflect.Array.getLength(array)
        return (0 until size).asSequence().map { java.lang.reflect.Array.get(array, it) }.iterator()
    }

    private fun quote(builder: StringBuilder, text: String) {
        builder.append('"').append(escape(if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text)).append('"')
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\"", "\\\"")

    private fun cap(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return if (oneLine.length <= MAX_CHARS) oneLine else oneLine.substring(0, MAX_CHARS - 1) + "…"
    }

    private fun safeToString(value: Any): String? = try {
        value.toString()
    } catch (e: Throwable) {
        null
    }

    private fun isJdkNumber(value: Any): Boolean =
        value is Number && value.javaClass.name.let { it.startsWith("java.lang.") || it.startsWith("java.math.") }

    private fun isUnsigned(value: Any): Boolean = value.javaClass.name in UNSIGNED

    private fun isStandardCollection(value: Any): Boolean = value is Collection<*> && isStandard(value.javaClass)

    private fun isStandardMap(value: Any): Boolean = value is Map<*, *> && isStandard(value.javaClass)

    /** The JDK's and the Kotlin standard library's own collections, whose iteration has no side effects. */
    private fun isStandard(type: Class<*>): Boolean = type.name.let { it.startsWith("java.util.") || it.startsWith("kotlin.collections.") }

    /** A Kotlin data class: its `toString` is generated and only reads its properties' fields. */
    private fun isDataClass(value: Any): Boolean {
        val type = value.javaClass
        return try {
            type.getMethod("component1") != null && type.methods.any { it.name == "copy" } &&
                type.getMethod("toString").declaringClass == type
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun typeName(value: Any?): String {
        val type = value?.javaClass ?: return "null"
        return type.simpleName.ifEmpty { type.name.substringAfterLast('.') }
    }

    private const val MAX_DEPTH = 3
    private val UNSIGNED = setOf("kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte")
}
