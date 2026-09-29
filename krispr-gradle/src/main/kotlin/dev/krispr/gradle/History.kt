package dev.krispr.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

/**
 * Verdicts of earlier runs, by stable mutant id, and the rules for reusing them (those of PIT 1.14.0's
 * IncrementalAnalyser, per declaration rather than per class). [settings] fingerprints what else decides
 * a verdict (krispr's version, the timeouts); a history written under other settings is ignored.
 */
internal class History(val settings: String, val entries: Map<Int, Entry>) {
    /**
     * One mutant's verdict: [hash] of its declaration's source text, [killer] the selector of the test
     * that killed it, [tests] the selectors that could kill it with the hash of each one's class, and
     * [names], [killedBy] and [millis] as the report showed them.
     */
    class Entry(
        val hash: String,
        val status: MutantStatus,
        val killer: String?,
        val tests: Map<String, String>,
        val names: List<String>,
        val killedBy: String?,
        val millis: Long,
    )

    /**
     * The earlier verdict of mutant [id] if it still holds, given its declaration's [hash] now, the
     * [tests] that may kill it now, and [classHash] of a test selector's class now. Nothing is reused
     * when the declaration changed. KILLED holds while the killing test still reaches the mutant and its
     * class is unchanged; SURVIVED (and TIMED_OUT and MEMORY_ERROR, which a test change could undo too)
     * while the reaching tests and their classes are all unchanged. Other verdicts are never reused.
     */
    fun reusable(id: Int, hash: String, tests: Collection<String>, classHash: (String) -> String): Entry? {
        val entry = entries[id] ?: return null
        if (hash.isEmpty() || entry.hash != hash) return null
        return when (entry.status) {
            MutantStatus.KILLED -> entry.takeIf { entry.killer != null && entry.killer in tests && entry.tests[entry.killer] == classHash(entry.killer) }
            MutantStatus.SURVIVED, MutantStatus.TIMED_OUT, MutantStatus.MEMORY_ERROR ->
                entry.takeIf { entry.tests.keys == tests.toSet() && tests.all { entry.tests[it] == classHash(it) } }
            else -> null
        }
    }

    /** The test that killed mutant [id] last time, whatever changed since; it runs first. */
    fun previousKiller(id: Int): String? = entries[id]?.takeIf { it.status == MutantStatus.KILLED }?.killer

    fun write(file: File) {
        val mutants = entries.toSortedMap().map { (id, e) ->
            linkedMapOf(
                "id" to id, "hash" to e.hash, "status" to e.status.name, "killer" to e.killer, "tests" to e.tests.toSortedMap(),
                "names" to e.names, "killedBy" to e.killedBy, "millis" to e.millis,
            )
        }
        val root = linkedMapOf("version" to VERSION, "settings" to settings, "mutants" to mutants)
        file.apply { parentFile?.mkdirs() }.writeText(JsonOutput.toJson(root) + "\n")
    }

    companion object {
        private const val VERSION = 1

        /** Statuses worth keeping: the only ones [reusable] may return. */
        val KEPT = setOf(MutantStatus.KILLED, MutantStatus.SURVIVED, MutantStatus.TIMED_OUT, MutantStatus.MEMORY_ERROR)

        /** The history in [file], or null when there is none, it is unreadable, or it was written under other [settings]. */
        fun read(file: File, settings: String): History? {
            if (!file.isFile) return null
            @Suppress("UNCHECKED_CAST")
            return try {
                val root = JsonSlurper().parse(file) as Map<String, Any?>
                if ((root["version"] as? Number)?.toInt() != VERSION || root["settings"] != settings) return null
                val entries = (root["mutants"] as List<Map<String, Any?>>).associate {
                    (it["id"] as Number).toInt() to Entry(
                        hash = it["hash"] as String,
                        status = MutantStatus.valueOf(it["status"] as String),
                        killer = it["killer"] as String?,
                        tests = (it["tests"] as Map<String, String>),
                        names = (it["names"] as List<String>),
                        killedBy = it["killedBy"] as String?,
                        millis = (it["millis"] as Number).toLong(),
                    )
                }
                History(settings, entries)
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}

/**
 * Hashes of compiled test classes, by class name: the class file and those of its nested and synthetic
 * classes (`Outer$Inner`, lambdas), in every test classes directory. "" when there is no class file.
 */
internal class TestClassHashes(private val dirs: Collection<File>) {
    private val cache = HashMap<String, String>()

    @Synchronized
    fun of(className: String): String = cache.getOrPut(className.substringBefore('$')) {
        val top = className.substringBefore('$')
        val path = top.replace('.', '/')
        val simple = path.substringAfterLast('/')
        val files = dirs.flatMap { dir ->
            File(dir, path).parentFile?.listFiles { file -> file.name == "$simple.class" || (file.name.startsWith("$simple$") && file.name.endsWith(".class")) }
                .orEmpty().toList()
        }.sortedBy { it.name }
        if (files.isEmpty()) return@getOrPut ""
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            digest.update(file.name.toByteArray())
            digest.update(file.readBytes())
        }
        digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
