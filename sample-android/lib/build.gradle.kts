plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("dev.krispr")
}

// AGP 8 compiles Kotlin through the separate kotlin-android plugin; AGP 9 has it built in.
if (providers.gradleProperty("krispr.agp").getOrElse("9").startsWith("8.")) apply(plugin = "org.jetbrains.kotlin.android")

android {
    namespace = "dev.krispr.sample.lib"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { buildConfig = true }
    // Robolectric reads resources through the unit test config AGP generates.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>().jvmToolchain(21)

dependencies {
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("com.squareup.moshi:moshi:1.15.2")
    // Generates CartJsonAdapter.kt into build/generated/ksp: Kotlin krispr must leave alone.
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
