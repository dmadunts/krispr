plugins {
    // AGP, KSP and Hilt versions follow -Pkrispr.agp (default 9.4.1); see settings.gradle.kts.
    id("com.android.application") apply false
    id("com.android.library") apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin) and this pins the Kotlin Gradle plugin it uses;
    // with AGP 8 the modules apply it as the separate kotlin-android plugin. Either way it has to be the
    // version krispr's compiler plugin is built against.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.20" apply false
    id("com.google.devtools.ksp") apply false
    id("com.google.dagger.hilt.android") apply false
}
