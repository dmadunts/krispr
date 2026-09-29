package dev.krispr.compiler

import java.io.File
import java.security.MessageDigest

/**
 * Stable mutant ids: a hash of (file relative to [root], enclosing declaration, operator, ordinal of
 * the mutant among that declaration's mutants of the same operator). Editing one declaration only
 * changes the ids inside it. Ids are positive Ints; a collision within the compilation is resolved by
 * rehashing, which is deterministic for a given set of sources.
 */
class MutantIds(private val root: File?) {
    private val ordinals = mutableMapOf<String, Int>()
    private val taken = mutableMapOf<Int, String>()

    fun assign(path: String, declaration: String, operator: Operator): Int {
        val scope = "${relative(path)}|$declaration|${operator.name}"
        val ordinal = ordinals.merge(scope, 1, Int::plus)!! - 1
        val key = "$scope|$ordinal"
        var attempt = 0
        while (true) {
            val id = hash(if (attempt == 0) key else "$key#$attempt")
            if (taken.putIfAbsent(id, key) == null) return id
            attempt++
        }
    }

    private fun relative(path: String): String {
        val base = root ?: return path
        val file = File(path)
        return if (file.startsWith(base)) file.relativeTo(base).invariantSeparatorsPath else path
    }

    private fun hash(key: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xff) shl 24) or ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
        // Never negative: Mutants.NONE is -1.
        return value and Int.MAX_VALUE
    }
}
