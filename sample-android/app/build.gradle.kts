plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("dev.krispr")
}

// AGP 8 compiles Kotlin through the separate kotlin-android plugin; AGP 9 has it built in.
if (providers.gradleProperty("krispr.agp").getOrElse("9").startsWith("8.")) apply(plugin = "org.jetbrains.kotlin.android")
// Only Hilt 2.59+ reads Kotlin 2.4 metadata, so the libraries stay on 2.60.1 even where AGP 8 needs
// the older Hilt Gradle plugin (settings.gradle.kts).
val hilt = "2.60.1"

android {
    namespace = "dev.krispr.sample.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.krispr.sample.app"
        minSdk = 24
    }
    buildFeatures { buildConfig = true }
}

extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>().jvmToolchain(21)

dependencies {
    implementation(project(":lib"))
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.dagger:hilt-android:$hilt")
    // Hilt_CartApp, the ViewModel modules and Dagger factories: generated, never mutated.
    ksp("com.google.dagger:hilt-compiler:$hilt")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
