package dev.krispr.gradle

import java.io.File

/**
 * What a survivor changed at its site, from `showChanges`'s two probe runs of its covering tests (mutant
 * off, then on): the first distinct values the site produced in each, and a one-line [text] for the
 * reports. [verdict] is one of [CHANGED], [SAME], [TYPE_ONLY] or [NOT_CAPTURED].
 */
internal data class Change(
    val verdict: String,
    val original: List<String> = emptyList(),
    val mutant: List<String> = emptyList(),
    val text: String,
    /** When the values seen were the same but came in another order: the first evaluation that differs. */
    val firstDifference: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "verdict" to verdict, "original" to original, "mutant" to mutant, "text" to text,
    ).apply { firstDifference?.let { put("firstDifference", it) } }

    companion object {
        const val CHANGED = "changed"
        const val SAME = "same"
        const val TYPE_ONLY = "typeOnly"
        const val NOT_CAPTURED = "notCaptured"

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: Map<*, *>): Change = Change(
            verdict = json["verdict"] as String,
            original = (json["original"] as? List<String>).orEmpty(),
            mutant = (json["mutant"] as? List<String>).orEmpty(),
            text = json["text"] as String,
            firstDifference = json["firstDifference"] as String?,
        )
    }
}

/**
 * The values one probe run recorded (see the runtime's `Probe` for the file format). A Robolectric
 * sandbox's own copy of the probe appends to the same file, so distinct values are deduplicated here.
 */
internal class ProbeValues(val distinct: List<String>, val opaque: Set<String>, val more: Boolean, val sequence: List<String>) {
    val isEmpty: Boolean get() = distinct.isEmpty()

    companion object {
        fun read(file: File): ProbeValues {
            val lines = if (file.isFile) file.readLines() else emptyList()
            val distinct = LinkedHashSet<String>()
            val opaque = HashSet<String>()
            var more = false
            val sequence = ArrayList<String>()
            for (line in lines) {
                val text = line.substringAfter('\t', "")
                when {
                    line.startsWith("D") -> if (distinct.size < ShowChanges.DISTINCT || text in distinct) distinct += text else more = true
                    line.startsWith("S") -> sequence += text
                    line.startsWith("M") -> more = true
                }
                if (line.length > 1 && line[1] == 'O') opaque += text
            }
            return ProbeValues(distinct.toList(), opaque, more, sequence)
        }
    }
}

internal object ShowChanges {
    const val DISTINCT = 3
    const val PROBE_PROPERTY = "krispr.probe"
    const val OUT_PROPERTY = "krispr.probeOut"

    /**
     * Operators the compiler plugin gives no probe (MutationTransformer.UNPROBED, less REMOVE_ASSIGNMENT,
     * which has its own): a statement whose value nothing reads, a coroutine context, a Flow operator, a
     * swallowed exception, extreme mode's emptied body. Their survivors are not re-run.
     */
    val UNPROBED = setOf(
        "REMOVE_CALL", "SAFE_CALL_BODY", "FLOW_EMIT", "LAUNCH_BODY", "CATCH_SWALLOW", "COROUTINE_CONTEXT", "FLOW_OPERATOR", "REMOVE_BODY",
    )

    const val SAME_TEXT = "same values observed — no test input makes the mutant differ (a missing case, or equivalent)"
    const val NOT_CAPTURED_TEXT = "not captured for this kind of site"

    fun notCaptured(why: String = NOT_CAPTURED_TEXT) = Change(Change.NOT_CAPTURED, text = why)

    /** Compares the two runs' values; null means that run did not finish (it timed out). */
    fun compare(original: ProbeValues?, mutant: ProbeValues?): Change {
        if (original == null || mutant == null) return notCaptured("not captured: a probe run timed out")
        if (original.isEmpty && mutant.isEmpty) return notCaptured("not captured: the probe saw no value at this site")
        val text = "original: ${values(original)} → mutant: ${values(mutant)}"
        if (original.distinct.toSet() != mutant.distinct.toSet() || original.more != mutant.more) {
            return Change(Change.CHANGED, original.distinct, mutant.distinct, text)
        }
        val index = original.sequence.indices.firstOrNull { it >= mutant.sequence.size || original.sequence[it] != mutant.sequence[it] }
            ?: mutant.sequence.size.takeIf { it > original.sequence.size }?.let { original.sequence.size }
        if (index != null) {
            val before = original.sequence.getOrNull(index) ?: "(no evaluation)"
            val after = mutant.sequence.getOrNull(index) ?: "(no evaluation)"
            val difference = "evaluation ${index + 1}: $before → $after"
            return Change(Change.CHANGED, original.distinct, mutant.distinct, "original: $before → mutant: $after (same values, $difference)", difference)
        }
        if (original.distinct.all { it in original.opaque } && mutant.distinct.all { it in mutant.opaque }) {
            return Change(Change.TYPE_ONLY, original.distinct, mutant.distinct, "same types observed (${values(original)}) — values not rendered")
        }
        return Change(Change.SAME, original.distinct, mutant.distinct, SAME_TEXT)
    }

    private fun values(values: ProbeValues): String =
        if (values.isEmpty) "(not reached)" else values.distinct.joinToString(", ") + if (values.more) ", …" else ""
}
