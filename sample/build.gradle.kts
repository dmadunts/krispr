plugins {
    kotlin("jvm") version "2.4.20"
    id("dev.krispr")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Read by TestEnvironmentTest: krispr's forks must inherit them.
    systemProperty("sample.unit", "celsius")
    environment("SAMPLE_REGION", "eu")
    // krispr records coverage serially regardless; mutant runs keep this.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}
