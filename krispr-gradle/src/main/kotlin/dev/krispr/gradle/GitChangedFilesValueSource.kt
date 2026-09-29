package dev.krispr.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * The absolute paths of files diff mode would mutate for [Params.ref] (see [ChangedLines]), read through a
 * `ValueSource` rather than inline in a task-configuration `Provider.map`: a `ValueSource` is never frozen
 * into a stored configuration-cache entry, so a cache reuse re-runs git and sees the working tree as it is
 * now, not as it was when the entry was written.
 */
internal abstract class GitChangedFilesValueSource : ValueSource<List<String>, GitChangedFilesValueSource.Params> {
    interface Params : ValueSourceParameters {
        val ref: Property<String>
        val directory: DirectoryProperty
    }

    override fun obtain(): List<String> =
        ChangedLines.since(parameters.ref.get(), parameters.directory.get().asFile).changedFiles.map { it.absolutePath }
}
