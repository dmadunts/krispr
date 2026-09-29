plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    id("dev.krispr")
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "dev.krispr.sample.kmp"
        compileSdk = 36
        minSdk = 24
        // Local unit tests for the Android target: the `testAndroidHostTest` task.
        withHostTest {}
    }
    // Non-JVM targets compile the same commonMain, uninstrumented: krispr mutates and tests commonMain only
    // through the JVM-hosted target (see docs/kmp.md for what native and JS would take).
    js(IR) { nodejs() }
    macosArm64()

    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

krispr {
    // The JVM target by default (commonMain + jvmMain, tested by jvmTest);
    // `-PkrisprTarget=android` mutates commonMain + androidMain against testAndroidHostTest instead.
    kotlinTarget.set(providers.gradleProperty("krisprTarget"))
}
