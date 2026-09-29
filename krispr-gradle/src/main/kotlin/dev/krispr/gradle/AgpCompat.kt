package dev.krispr.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasHostTests
import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.Variant
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

/**
 * Everything krispr takes from the Android Gradle plugin, all through its public Variant API
 * (`androidComponents`): which variants exist, and which `Test` task runs each one's local (host)
 * unit tests. AGP is compileOnly, so this class is only loaded once an Android plugin is applied.
 *
 * Built against AGP 9.4, and runs on AGP 8.1 and newer:
 * - AGP 8.5+: `HasHostTests`, whose `configureTestTask` hands over the test task itself ([HostTests]).
 * - AGP 8.1–8.4: `HasUnitTest`, which only names the component; the task is then found by its name,
 *   `test<Component>` (`testDebugUnitTest`), which is what every AGP 8 and 9 release calls it.
 */
internal class AndroidUnitTests private constructor() {
    /** Variant name → name of its unit test component (`debugUnitTest`), or null when it has none. */
    private val variants = linkedMapOf<String, String?>()
    private val tasks = mutableMapOf<String, Test>()

    /** The single variant with unit tests, for targets that have no build types (KMP Android libraries). */
    fun onlyVariant(): String? = variants.filterValues { it != null }.keys.singleOrNull()

    fun testTask(project: Project, variant: String): Test {
        if (variant !in variants) {
            throw GradleException("krispr: ${project.path} has no Android variant '$variant' (krispr.androidVariant); variants: ${variants.keys.joinToString()}")
        }
        val component = variants[variant]
            ?: throw GradleException("krispr: variant '$variant' of ${project.path} has no local unit tests")
        tasks[variant]?.let { return it }
        // Realizing the test tasks runs AGP's configureTestTask callbacks, which fill in [tasks].
        project.tasks.withType(Test::class.java).toList()
        return tasks[variant]
            ?: project.tasks.findByName("test" + component.replaceFirstChar { it.uppercaseChar() }) as? Test
            ?: throw GradleException("krispr: found no unit test task for variant '$variant' of ${project.path}")
    }

    companion object {
        fun observe(project: Project): AndroidUnitTests {
            val result = AndroidUnitTests()
            @Suppress("UNCHECKED_CAST")
            val components = project.extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, Variant>
            components.onVariants(components.selector().all()) { variant ->
                val hostTest = if (HOST_TESTS_API) HostTests.unitTest(variant) { task -> result.tasks[variant.name] = task } else null
                @Suppress("DEPRECATION")
                result.variants[variant.name] = hostTest ?: (variant as? HasUnitTest)?.unitTest?.name
            }
            return result
        }

        /**
         * True for the target of `com.android.kotlin.multiplatform.library`, whatever platform type it
         * reports: `jvm` on AGP 8, `androidJvm` on AGP 9. Its DSL type was renamed along the way.
         */
        fun isAndroidTarget(target: KotlinTarget): Boolean = KMP_ANDROID_TARGETS.any { it.isInstance(target) }

        private val KMP_ANDROID_TARGETS = listOf(
            "com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget",
            "com.android.build.api.dsl.KotlinMultiplatformAndroidTarget",
        ).mapNotNull(::agpClass)

        /** `HasHostTests` and `HostTest` arrived in AGP 8.5. */
        private val HOST_TESTS_API: Boolean = agpClass("com.android.build.api.variant.HasHostTests") != null

        private fun agpClass(name: String): Class<*>? = try {
            Class.forName(name, false, AndroidComponentsExtension::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}

/** The AGP 8.5+ host test API, in its own class so older AGP never has to resolve it. */
private object HostTests {
    /** The name of [variant]'s unit test component, handing its `Test` task to [onTask] once it is created. */
    fun unitTest(variant: Variant, onTask: (Test) -> Unit): String? {
        val hostTest = (variant as? HasHostTests)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE) ?: return null
        hostTest.configureTestTask(onTask)
        return hostTest.name
    }
}
