plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 4 (#43): tests that pass in a fresh JVM and fail when a reused one runs them again.
dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
}
