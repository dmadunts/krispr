plugins {
    id("com.android.library") apply false
    // AGP 9 compiles Kotlin itself; this pins the Kotlin Gradle plugin it uses to the version krispr's
    // compiler plugin is built against.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

// Every module is an Android library on the same SDKs and toolchain; each build file adds its own tests.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "dev.krispr.playground.${project.name}"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            // Robolectric reads resources through the unit test config AGP generates.
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
        extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>().jvmToolchain(21)
        dependencies { "testImplementation"("junit:junit:4.13.2") }
    }
}
