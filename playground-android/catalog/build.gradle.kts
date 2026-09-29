plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 1 (size skew, #41): the big Robolectric module, most of the build's mutants.
dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
}
