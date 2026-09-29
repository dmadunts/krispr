plugins {
    id("krispr.compiler-variant")
}

// Kotlin 2.3.0 to 2.3.10. Built against 2.3.0, it fails on 2.3.20 (IrDeclarationOrigin changed shape).
compilerVariant {
    kotlin = "2.3.0"
    testOn("2.3.0", kctfork = "0.12.1")
    testOn("2.3.10", kctfork = "0.12.1")
}
