pluginManagement {
    // Resolves id("dev.krispr") from the local krispr-gradle project.
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // -Pkrispr.agp=8.13.2 builds against another AGP; scripts/agp-gradlew picks the Gradle version to match.
    // The floor is 8.5.2, the oldest AGP the Kotlin 2.4.20 Gradle plugin accepts.
    val agp = providers.gradleProperty("krispr.agp").getOrElse("9.4.1")
    val (major, minor) = agp.split('.').take(2).map { it.takeWhile(Char::isDigit).toInt() }
    require(major > 8 || major == 8 && minor >= 5) { "sample-android needs AGP 8.5.2 or newer (Kotlin 2.4.20's minimum); got $agp" }
    // KSP 2.3.6+ requires AGP 8.12; 2.3.4 still runs on older AGP 8.
    val ksp = if (major == 8 && minor < 12) "2.3.4" else "2.3.12"
    // The Hilt Gradle plugin requires AGP 9 from 2.59; 2.57.2 drives the 2.60.1 libraries fine on AGP 8.
    val hilt = if (major == 8) "2.57.2" else "2.60.1"
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) useVersion(agp)
            if (requested.id.id == "com.google.dagger.hilt.android") useVersion(hilt)
            if (requested.id.id == "com.google.devtools.ksp") useVersion(ksp)
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "krispr-sample-android"

include(":lib", ":app")

// Substitutes dev.krispr:krispr-compiler and dev.krispr:krispr-runtime with the local projects.
includeBuild("..")
