plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 5: caches a surviving mutant fills, which hide later mutants in a reused sandbox.
dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
}
