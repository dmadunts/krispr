plugins {
    id("krispr.compiler-variant")
}

// Kotlin 2.4.x.
compilerVariant {
    kotlin = "2.4.0"
    testOn("2.4.0", kctfork = "0.13.0")
    testOn("2.4.20", kctfork = "0.14.0")
    // Full matrix only (-Pkrispr.fullMatrix=true): a middle patch release between the endpoints above.
    testOnMiddle("2.4.10", kctfork = "0.13.0")
}
