import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("krispr.publish")
}

description = "Krispr runtime: the mutant switch, the coverage recorder and the test runner"

kotlin {
    jvmToolchain(21)
    // On the compile classpath of the instrumented compilation, so the oldest Kotlin krispr supports
    // (2.1.20) must read its metadata (compilers read one version ahead) and must not get a newer stdlib.
    coreLibrariesVersion = "2.1.20"
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        // Loaded into the test JVM of the project under test, which may still run on Java 8.
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // Provided by the test runtime classpath of the project under test; only the recording
    // listener and the forked runner touch it, and neither is loaded in production.
    // JUnit 5.x's platform, the oldest API the runtime uses; it still runs on Java 8.
    compileOnly(libs.junit.platform.launcher.compat)
    compileOnly(libs.robolectric.pluginapi)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
}

// The runtime ships Java 8 bytecode; its own tests run on the build JDK with the current JUnit.
listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
tasks.compileTestKotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21); freeCompilerArgs.set(listOf("-Xsuppress-version-warnings")) } }
tasks.compileTestJava { targetCompatibility = "21" }

tasks.test {
    useJUnitPlatform()
}
