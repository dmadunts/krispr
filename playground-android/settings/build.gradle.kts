plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 3 (#43): nearly every mutant is killed by a Robolectric test, so each kill retires a worker.
dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
}
