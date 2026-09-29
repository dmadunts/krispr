package dev.krispr.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

/** `mutate=<category>` for every arid-code category the build turned back on (see AridCode in the compiler). */
internal fun aridOptions(extension: KrisprExtension): List<SubpluginOption> =
    listOf(
        extension.mutateComposables to "composables",
        extension.mutateLogging to "logging",
        extension.mutateDependencyInjection to "dependencyInjection",
        extension.mutateToString to "toString",
        extension.mutateTrivialGetters to "trivialGetters",
        extension.mutateCaches to "caches",
        extension.mutateDelays to "delays",
        extension.mutateMetrics to "metrics",
        extension.mutateGenerated to "generated",
    ).filter { (enabled, _) -> enabled.orNull == true }.map { (_, option) -> SubpluginOption("mutate", option) }


internal const val EXTREME = "extreme"

/** `krispr.mode`, with `-Pkrispr.mode` winning over the build script. */
internal fun modeOf(project: Project, extension: KrisprExtension): Provider<String> =
    project.providers.gradleProperty("krispr.mode").orElse(extension.mode).orElse("default").map { mode ->
        mode.trim().lowercase().also {
            if (it != "default" && it != EXTREME) throw GradleException("krispr: mode must be 'default' or '$EXTREME', not '$mode'")
        }
    }

/** `krispr.operators`, with `-Pkrispr.operators=A,B` winning over the build script. */
internal fun operatorsOf(project: Project, extension: KrisprExtension): Provider<List<String>> =
    project.providers.gradleProperty("krispr.operators").map { value -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(extension.operators).orElse(emptyList())

/** `krispr.showChanges`, with `-Pkrispr.showChanges` winning over the build script. Default false. */
internal fun showChangesOf(project: Project, extension: KrisprExtension): Provider<Boolean> =
    project.providers.gradleProperty("krispr.showChanges").map { it.trim().toBoolean() }.orElse(extension.showChanges).orElse(false)

/** `operator=<name>` per selected operator, and `mode=extreme`; the compiler plugin checks the names. */
internal fun operatorOptions(project: Project, extension: KrisprExtension): List<SubpluginOption> =
    operatorsOf(project, extension).get().map { SubpluginOption("operator", it) } +
        listOfNotNull(
            SubpluginOption("mode", EXTREME).takeIf { modeOf(project, extension).get() == EXTREME },
            // Only with showChanges: the value probes are otherwise not emitted at all.
            SubpluginOption("probe", "true").takeIf { showChangesOf(project, extension).get() },
        )

/** `krispr.targetFiles`, with `-Pkrispr.targetFiles=a.kt,b.kt` winning over the build script. */
internal fun targetFilesOf(project: Project, extension: KrisprExtension): Provider<List<String>> =
    project.providers.gradleProperty("krispr.targetFiles").map { value -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(extension.targetFiles).orElse(emptyList())

/**
 * `krispr.diffBase`, with `-Pkrispr.diffBase=<ref>` winning over the build script. `krisprRun --since`
 * is a separate, task-only override (see [KrisprRunTask.since]) that filters mutants by line but does not
 * reach here, so it does not narrow instrumentation.
 */
internal fun diffBaseOf(project: Project, extension: KrisprExtension): Provider<String> =
    project.providers.gradleProperty("krispr.diffBase").orElse(extension.diffBase)

/**
 * With `targetFiles` set, or otherwise with `diffBase` set, `excludeDir=<path>` for every file and directory
 * under the project directory and the compilation's source directories that holds none of the targets: the
 * compiler only knows exclusions, and a path excludes everything under it (a file, itself). Only the
 * directories on the way to a target are listed, so the options stay few.
 *
 * `targetFiles` and diff-derived targets fail differently when a target lands outside every source
 * directory: an explicit `targetFiles` entry outside every root is almost certainly a typo, so it fails the
 * build loudly (it would otherwise silently exclude everything). A diff commonly touches files outside this
 * compilation too — tests, docs, another module's sources, the build script itself — so those are dropped
 * without complaint; only the changed files that do land here decide the exclusions. A diff with no changed
 * file in this compilation excludes everything (zero mutants), which is diff mode's point: no line changed
 * here means nothing to mutate here, never "fall back to the whole module".
 */
internal fun targetOptions(project: Project, extension: KrisprExtension, sourceDirs: Collection<File>): List<SubpluginOption> {
    val explicit = targetFilesOf(project, extension).get()
    val diffRef = if (explicit.isEmpty()) diffBaseOf(project, extension).orNull else null
    if (explicit.isEmpty() && diffRef == null) return emptyList()

    val projectDir = project.projectDir.canonicalFile
    // For validating an explicit target: anywhere in the project counts, even outside a recognised
    // source directory (a clearer error than a silent "excludes everything").
    val validationRoots = listOf(projectDir) + sourceDirs.map { it.canonicalFile }.filter { it.isDirectory && !it.startsWith(projectDir) }
    // For the walk that computes excludeDir: only the compilation's own source directories. Never the
    // project directory itself — listing it would make Gradle's configuration cache track the whole
    // project tree (build output, .git, .gradle, …) as an input, invalidating on every build.
    val walkRoots = sourceDirs.map { it.canonicalFile }.filter { it.isDirectory }

    val targets: Set<File> = if (explicit.isNotEmpty()) {
        val files = explicit.map { project.file(it).canonicalFile }.toSet()
        files.firstOrNull { !it.isFile }?.let { throw GradleException("krispr: target file $it does not exist") }
        files.firstOrNull { target -> validationRoots.none { target.startsWith(it) } }?.let {
            throw GradleException(
                "krispr: target file $it is outside the project directory ($projectDir) and every compilation " +
                    "source directory (${validationRoots.drop(1).joinToString(", ").ifEmpty { "none" }}); it would be " +
                    "mutated nowhere, so krispr.targetFiles would silently exclude everything"
            )
        }
        files
    } else {
        val diffFiles = project.providers.of(GitChangedFilesValueSource::class.java) { spec ->
            spec.parameters.ref.set(diffRef)
            spec.parameters.directory.set(project.layout.projectDirectory)
        }.get().map { File(it).canonicalFile }.filter { it.extension == "kt" }
        diffFiles.filterTo(HashSet()) { target -> walkRoots.any { target.startsWith(it) } }
    }

    val excluded = mutableListOf<File>()
    fun walk(dir: File) {
        for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
            when {
                child in targets -> Unit
                child.isDirectory && targets.any { it.startsWith(child) } -> walk(child)
                else -> excluded += child
            }
        }
    }
    for (root in walkRoots.distinct()) if (targets.any { it.startsWith(root) }) walk(root) else excluded += root
    return excluded.map { SubpluginOption("excludeDir", it.absolutePath) }
}
