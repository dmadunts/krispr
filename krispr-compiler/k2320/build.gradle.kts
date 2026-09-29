plugins {
    id("krispr.compiler-variant")
}

// Kotlin 2.3.20 to 2.3.x.
compilerVariant {
    kotlin = "2.3.20"
    testOn("2.3.20", kctfork = "0.12.1")
    testOn("2.3.21", kctfork = "0.12.1")
}
