plugins {
    id("com.android.library")
    id("dev.krispr")
}

// Shape 7 (#42): CPU-bound tests of a few hundred ms each (once instrumented), for timeouts under host load.
// Plain JUnit: a kill does not retire the worker, so the module's cost is the tests themselves.
