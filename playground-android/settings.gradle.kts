pluginManagement {
    // Resolves id("dev.krispr") from the local krispr-gradle project, as sample-android does.
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) useVersion("9.4.1")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "krispr-playground-android"

// One big Robolectric module and eight small ones; README.md says which shape and issue each one is for.
include(":catalog", ":checkout", ":settings", ":session", ":pricing", ":mocks", ":feed", ":status", ":units")

// Substitutes dev.krispr:krispr-compiler and dev.krispr:krispr-runtime with the local projects.
includeBuild("..")
