package dev.krispr.sample

import kotlin.math.floor

/** ARGUMENT_PROPAGATION: same-type text, collection and rounding transforms skipped. */
object Labels {
    fun version(tag: String): String = tag.removePrefix("v").removeSuffix("-SNAPSHOT")

    fun slug(title: String): String = title.trim().replace(' ', '-')

    fun host(url: String): String = url.substringAfter("://").substringBefore('/')

    fun display(name: String): String? = name.takeIf { it.isNotBlank() }

    fun title(name: String): String = name.ifBlank { "Untitled" }

    fun tags(base: List<String>, extra: String): List<String> = base + extra

    fun untagged(tags: Set<String>, tag: String): Set<String> = tags - tag

    fun wholeKilos(grams: Double): Double = floor(grams / 1000)
}
