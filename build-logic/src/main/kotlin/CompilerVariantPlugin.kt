import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSetOutput
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * One build of the krispr compiler plugin for a range of Kotlin releases. The compiler's IR API is not
 * binary compatible across releases (not even across patch-level feature releases like 2.3.0 → 2.3.20),
 * so the shared sources in `krispr-compiler/src` are compiled once per range, together with the
 * variant's own `src/main/kotlin` (the APIs that differ), against the oldest release of the range. The
 * Gradle plugin picks the artifact matching the consumer's Kotlin Gradle plugin.
 *
 * The shared tests compile once, against the newest release given to [CompilerVariantExtension.testOn],
 * and run on the compiler of every release given to it: `test` on the newest, `testKotlin<version>` on
 * the others, all part of `check`.
 *
 * [CompilerVariantExtension.testOnMiddle] adds one further `testKotlin<version>` task for a patch release
 * in between the range's oldest and newest, so the matrix exercises more than just the two endpoints. Its
 * task is registered like any other but only wired into `check` under `-Pkrispr.fullMatrix=true`, since
 * running it on every build would multiply CI time for a release the endpoints already cover most of.
 */
abstract class CompilerVariantExtension {
    /** The Kotlin release this artifact compiles against: the oldest one it serves. */
    abstract val kotlin: Property<String>

    internal val tests = mutableListOf<Pair<String, String>>()
    internal val middleTests = mutableListOf<Pair<String, String>>()

    /** Runs the compiler tests on Kotlin [version] with the kctfork release [kctfork] built for its line. */
    fun testOn(version: String, kctfork: String) {
        tests += version to kctfork
    }

    /** Like [testOn], but only part of `check` under `-Pkrispr.fullMatrix=true`; see the class doc. */
    fun testOnMiddle(version: String, kctfork: String) {
        middleTests += version to kctfork
    }
}

class CompilerVariantPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // The compiler brings its own stdlib; the plugin must not drag a newer one onto its classpath.
        project.extensions.extraProperties["kotlin.stdlib.default.dependency"] = "false"
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply(PublishConventionPlugin::class.java)
        project.description = "Krispr's Kotlin compiler plugin, built for one range of Kotlin releases"
        val variant = project.extensions.create("compilerVariant", CompilerVariantExtension::class.java)
        val shared = project.rootDir.resolve("krispr-compiler/src")

        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        kotlin.jvmToolchain(21)
        kotlin.compilerOptions {
            optIn.addAll(
                "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
                "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
            )
            // Only stdlib API that the oldest served compiler ships, and metadata it reads (the tests load
            // these classes into that compiler).
            val minor = variant.kotlin.map { KotlinVersion.fromVersion(it.split('.').take(2).joinToString(".")) }
            apiVersion.set(minor)
            languageVersion.set(minor)
            // 2.1 is deprecated, but it is the one the 2.1.20 variant needs.
            freeCompilerArgs.add("-Xsuppress-version-warnings")
        }
        kotlin.sourceSets.getByName("main") {
            it.kotlin.srcDir(shared.resolve("main/kotlin"))
            it.resources.srcDir(shared.resolve("main/resources"))
        }
        kotlin.sourceSets.getByName("test") { it.kotlin.srcDir(shared.resolve("test/kotlin")) }

        val deps = project.dependencies
        deps.addProvider("compileOnly", variant.kotlin.map { "org.jetbrains.kotlin:kotlin-compiler-embeddable:$it" })
        deps.addProvider("compileOnly", variant.kotlin.map { "org.jetbrains.kotlin:kotlin-stdlib:$it" })
        deps.add("testImplementation", deps.project(mapOf("path" to ":krispr-runtime")))
        deps.add("testImplementation", "androidx.compose.runtime:runtime:${project.catalogVersion("compose-runtime")}")
        deps.add("testImplementation", deps.platform("org.junit:junit-bom:${project.catalogVersion("junit")}"))
        deps.add("testImplementation", "org.junit.jupiter:junit-jupiter")
        deps.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

        project.tasks.withType(Test::class.java).configureEach { test ->
            test.useJUnitPlatform()
            // Compile-testing runs the Kotlin compiler in-process.
            test.maxHeapSize = "1g"
        }

        project.afterEvaluate { configureTests(project, variant) }
    }

    private fun configureTests(project: Project, variant: CompilerVariantExtension) {
        check(variant.tests.isNotEmpty()) { "${project.path}: declare the Kotlin releases to test on with compilerVariant.testOn" }
        val (newest, newestKctfork) = variant.tests.last()
        val deps = project.dependencies
        for (name in listOf("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-compose-compiler-plugin-embeddable")) {
            deps.add("testImplementation", "org.jetbrains.kotlin:$name:$newest")
        }
        deps.add("testImplementation", "dev.zacsweers.kctfork:core:$newestKctfork")
        val testRuntime = project.configurations.getByName("testRuntimeClasspath")
        listOf(testRuntime, project.configurations.getByName("testCompileClasspath")).forEach { pinKotlin(it, newest, newestKctfork) }
        project.tasks.named("test", Test::class.java) { it.description = "Runs the compiler tests on Kotlin $newest." }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val testOutput = sourceSets.getByName("test").output
        val mainOutput = sourceSets.getByName("main").output
        for ((version, kctfork) in variant.tests.dropLast(1)) {
            val task = registerVersionTest(project, version, kctfork, testRuntime, testOutput, mainOutput)
            project.tasks.named("check") { it.dependsOn(task) }
        }

        // Full version matrix: also test one patch release in between the range's endpoints, so the suite
        // is not only exercising the two Kotlin releases the endpoint tests already cover.
        val fullMatrix = project.providers.gradleProperty("krispr.fullMatrix").orElse("false").get() == "true"
        for ((version, kctfork) in variant.middleTests) {
            val task = registerVersionTest(project, version, kctfork, testRuntime, testOutput, mainOutput)
            if (fullMatrix) project.tasks.named("check") { it.dependsOn(task) }
        }
    }

    /** Registers `testKotlin<version>`, running the already-compiled shared tests against Kotlin [version]. */
    private fun registerVersionTest(
        project: Project,
        version: String,
        kctfork: String,
        testRuntime: Configuration,
        testOutput: SourceSetOutput,
        mainOutput: SourceSetOutput,
    ): TaskProvider<Test> {
        val suffix = version.replace('.', '_')
        // The test runtime classpath with this release's compiler, stdlib, Compose plugin and kctfork.
        val runtime = project.configurations.create("testRuntimeKotlin$suffix") { c ->
            c.isCanBeConsumed = false
            c.isCanBeResolved = true
            c.extendsFrom(project.configurations.getByName("testImplementation"), project.configurations.getByName("testRuntimeOnly"))
            for (key in testRuntime.attributes.keySet()) {
                @Suppress("UNCHECKED_CAST")
                c.attributes.attribute(key as Attribute<Any>, testRuntime.attributes.getAttribute(key)!!)
            }
            pinKotlin(c, version, kctfork)
        }
        return project.tasks.register("testKotlin$suffix", Test::class.java) { test ->
            test.description = "Runs the compiler tests on Kotlin $version."
            test.group = "verification"
            test.testClassesDirs = testOutput.classesDirs
            test.classpath = testOutput + mainOutput + runtime
        }
    }

    /** Every Kotlin artifact at [version], so kctfork's compiles see one consistent compiler and stdlib. */
    private fun pinKotlin(configuration: Configuration, version: String, kctfork: String) {
        configuration.resolutionStrategy.eachDependency { details ->
            val requested = details.requested
            when {
                requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-") -> details.useVersion(version)
                requested.group == "dev.zacsweers.kctfork" -> details.useVersion(kctfork)
            }
        }
    }

    private fun Project.catalogVersion(name: String): String =
        extensions.getByType(VersionCatalogsExtension::class.java).named("libs").findVersion(name).get().requiredVersion
}
