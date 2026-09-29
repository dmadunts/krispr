package dev.krispr.sample.kmp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** One published release of a package. */
data class Release(val version: Version, val downloads: Int, val yanked: Boolean = false)

enum class Channel { STABLE, PREVIEW }

/** Which channel a release belongs to; a pre-release is a preview, as is anything before 1.0.0. */
fun channelOf(version: Version): Channel =
    if (version.pre != null || version.major == 0) Channel.PREVIEW else Channel.STABLE

/** A release worth suggesting: not yanked, on the requested channel, and used by at least [minDownloads]. */
fun suggestable(release: Release, channel: Channel, minDownloads: Int): Boolean {
    if (release.yanked) return false
    if (channel == Channel.STABLE && channelOf(release.version) != Channel.STABLE) return false
    return release.downloads >= minDownloads
}

/** Parses a stream of `version downloads` lines, dropping the ones that do not parse or are not suggestable. */
fun suggestions(lines: Flow<String>, channel: Channel, minDownloads: Int = 100): Flow<Version> =
    lines
        .mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 2) return@mapNotNull null
            val version = Version.parse(parts[0]) ?: return@mapNotNull null
            val downloads = parts[1].toIntOrNull() ?: return@mapNotNull null
            Release(version, downloads)
        }
        .filter { suggestable(it, channel, minDownloads) }
        .map { it.version }
