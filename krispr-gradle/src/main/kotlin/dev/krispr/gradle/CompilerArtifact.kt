package dev.krispr.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * Which build of the compiler plugin a Kotlin release gets. The IR API krispr uses is not binary
 * compatible across Kotlin releases, so `krispr-compiler-<variant>` is compiled once per range of
 * releases (krispr-compiler/k* in this repository). Keep in sync with the root settings.gradle.kts.
 */
internal object CompilerArtifact {
    /** Oldest release each artifact serves, newest first. */
    private val VARIANTS = listOf(
        KotlinRelease(2, 4, 0) to "k240",
        KotlinRelease(2, 3, 20) to "k2320",
        KotlinRelease(2, 3, 0) to "k230",
        KotlinRelease(2, 2, 0) to "k220",
        KotlinRelease(2, 1, 20) to "k2120",
    )

    /** The first release that krispr has not been built and tested against. */
    private val UNSUPPORTED_FROM = KotlinRelease(2, 5, 0)

    /** Fallback when the actual compiler cannot be resolved: the Kotlin Gradle plugin driving this build. */
    val kotlinGradlePluginVersion: String by lazy { getKotlinPluginVersion(Logging.getLogger(CompilerArtifact::class.java)) }

    /** `krispr-compiler-<variant>` for the compiler [project] actually resolves, or a build failure naming the supported range. */
    fun artifactId(project: Project): String = artifactId(resolvedKotlinVersion(project))

    internal fun artifactId(kotlinVersion: String): String {
        val release = KotlinRelease.parse(kotlinVersion)
        val variant = release?.takeIf { it < UNSUPPORTED_FROM }?.let { r -> VARIANTS.firstOrNull { (from, _) -> r >= from }?.second }
            ?: throw GradleException(
                "krispr supports Kotlin ${VARIANTS.last().first} up to (not including) $UNSUPPORTED_FROM, but the " +
                    "compiler this build resolves is $kotlinVersion. Use a supported Kotlin version for the krispr run, " +
                    "or a krispr release that supports $kotlinVersion.",
            )
        return "krispr-compiler-$variant"
    }

    /**
     * The Kotlin compiler version [project] actually compiles with: the resolved `kotlin-compiler-embeddable`
     * version on its `kotlinCompilerClasspath` configuration (what the Kotlin Gradle plugin runs the
     * compiler daemon, or the in-process compiler, from) or its `compileClasspath`, either of which can
     * differ from the Kotlin Gradle plugin's own version. Falls back to [kotlinGradlePluginVersion] when
     * neither resolves one: an older Kotlin Gradle plugin without that configuration, or resolution failing.
     */
    internal fun resolvedKotlinVersion(project: Project): String =
        compilerEmbeddableVersion(project, "kotlinCompilerClasspath")
            ?: compilerEmbeddableVersion(project, "compileClasspath")
            ?: kotlinGradlePluginVersion

    private fun compilerEmbeddableVersion(project: Project, configurationName: String): String? {
        val configuration = project.configurations.findByName(configurationName)?.takeIf { it.isCanBeResolved } ?: return null
        val versions = runCatching {
            configuration.resolvedConfiguration.lenientConfiguration.artifacts.map {
                Triple(it.moduleVersion.id.group, it.moduleVersion.id.name, it.moduleVersion.id.version)
            }
        }.getOrElse { emptyList() }
        return compilerEmbeddableVersion(versions)
    }

    /** Pure lookup, so the resolution logic above is testable without a real Gradle [Project]. */
    internal fun compilerEmbeddableVersion(artifacts: List<Triple<String, String, String>>): String? =
        artifacts.firstOrNull { (group, name, _) -> group == "org.jetbrains.kotlin" && name == "kotlin-compiler-embeddable" }?.third

    /** `-Xcompiler-plugin-order` arrived in Kotlin 2.3.0; older compilers warn about the unknown flag. */
    fun supportsPluginOrder(kotlinVersion: String = kotlinGradlePluginVersion): Boolean =
        KotlinRelease.parse(kotlinVersion)?.let { it >= KotlinRelease(2, 3, 0) } ?: true

    data class KotlinRelease(val major: Int, val minor: Int, val patch: Int) : Comparable<KotlinRelease> {
        override fun compareTo(other: KotlinRelease): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            /** `2.3.20`, `2.3.20-RC2` or `2.4.0-Beta1`: pre-releases count as the release they precede. */
            fun parse(version: String): KotlinRelease? {
                val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(version) ?: return null
                val (major, minor, patch) = match.destructured
                return KotlinRelease(major.toInt(), minor.toInt(), patch.toInt())
            }
        }
    }
}
