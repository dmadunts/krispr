package dev.krispr.fixture

/** Static state outside the runtime's package, which [dev.krispr.runtime.IsolatingClassLoader] reloads. */
object StaticCounter {
    @JvmStatic
    var count = 0
}
