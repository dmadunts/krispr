plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// group and version come from gradle.properties: an allprojects {} block here breaks consumers that
// include this build with Gradle's isolated projects enabled (nowinandroid does).
