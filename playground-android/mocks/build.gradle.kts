plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 6 (#36): MockK relaxed mocks, mocks of function types and of enums, under Robolectric.
//
// -Pplayground.issue36=true adds LegacySdkNotifierTest, whose @Config(sdk = [34]) makes a second sandbox,
// and runs each test class in its own JVM, as apps do to bound Robolectric's memory. testDebugUnitTest
// passes; krisprRecord, which runs the whole suite in one JVM, fails TierTest with #36's "Can't instantiate
// proxy for class kotlin.Function1" (duplicate class definition for kotlin.jvm.functions.Function1$Subclass0).
val issue36 = providers.gradleProperty("playground.issue36").map(String::toBoolean).getOrElse(false)

if (issue36) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        sourceSets.getByName("test").kotlin.directories += "src/issue36/kotlin"
    }
}

if (issue36) tasks.withType<Test>().configureEach { forkEvery = 1 }

dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.mockk:mockk:1.14.11")
}
