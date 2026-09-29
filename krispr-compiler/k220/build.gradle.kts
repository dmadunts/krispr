plugins {
    id("krispr.compiler-variant")
}

// Kotlin 2.2.x.
compilerVariant {
    kotlin = "2.2.0"
    testOn("2.2.0", kctfork = "0.8.0")
    testOn("2.2.21", kctfork = "0.11.1")
    // Full matrix only (-Pkrispr.fullMatrix=true): a middle patch release between the endpoints above.
    testOnMiddle("2.2.10", kctfork = "0.8.0")
}
