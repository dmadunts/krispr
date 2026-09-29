pluginManagement {
    // Resolves id("dev.krispr") from the local krispr-gradle project.
    includeBuild("..")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // `-Psample.kotlin=2.1.20` builds the sample with another Kotlin release, to check krispr on it.
    val kotlinOverride = providers.gradleProperty("sample.kotlin").orNull
    resolutionStrategy.eachPlugin {
        if (kotlinOverride != null && requested.id.id.startsWith("org.jetbrains.kotlin.")) useVersion(kotlinOverride)
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "krispr-sample"

// Substitutes dev.krispr:krispr-compiler-<variant> and dev.krispr:krispr-runtime with the local projects.
includeBuild("..")
