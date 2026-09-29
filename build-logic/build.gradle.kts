import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Runs on Gradle's embedded Kotlin stdlib, which is older than the compiler we build with.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    // The root build puts the Kotlin Gradle plugin on the classpath; this only compiles against it.
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("compilerVariant") {
            id = "krispr.compiler-variant"
            implementationClass = "CompilerVariantPlugin"
        }
    }
}
