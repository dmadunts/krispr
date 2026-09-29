pluginManagement {
    // Convention plugin for the per-Kotlin-version compiler plugin builds.
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose runtime for the compiler plugin's Compose ordering test; AGP API for the Gradle plugin.
        google()
    }
}

rootProject.name = "krispr"

include("krispr-runtime", "krispr-gradle")

// The compiler plugin, built once per range of Kotlin releases (see build-logic). Keep in sync with
// COMPILER_VARIANTS in krispr-gradle's CompilerArtifact.kt.
for (variant in listOf("k2120", "k220", "k230", "k2320", "k240")) {
    include("krispr-compiler-$variant")
    project(":krispr-compiler-$variant").projectDir = file("krispr-compiler/$variant")
}
