plugins {
    id("krispr.compiler-variant")
}

// Kotlin 2.1.20 to 2.1.21 (2.1.20 introduced the unified parameter and argument lists krispr relies on).
compilerVariant {
    kotlin = "2.1.20"
    testOn("2.1.20", kctfork = "0.7.1")
    testOn("2.1.21", kctfork = "0.7.1")
}
