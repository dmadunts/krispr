pluginManagement {
    // Resolves id("dev.krispr") from the local krispr-gradle project.
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "krispr-sample-kmp"

// Substitutes dev.krispr:krispr-compiler-<variant> and dev.krispr:krispr-runtime with the local projects.
includeBuild("..")
