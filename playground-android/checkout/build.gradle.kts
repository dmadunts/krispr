plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 2: a Robolectric module whose mutants are mostly covered only by plain JUnit tests.
dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
}
