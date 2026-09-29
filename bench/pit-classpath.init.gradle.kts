// Dumps what PIT needs to mutate a module the same way Krispr does: the test task's runtime
// classpath, its test class dirs, and the module's main class dirs. Used by bench/run-pit.sh.
allprojects {
    val testTaskName = providers.gradleProperty("bench.testTask").orNull ?: return@allprojects
    val out = providers.gradleProperty("bench.out").orNull ?: return@allprojects
    tasks.register("benchClasspath") {
        val testTask = tasks.named(testTaskName, Test::class.java)
        dependsOn(testTask.map { it.taskDependencies })
        doLast {
            val t = testTask.get()
            java.io.File(out).apply { mkdirs() }
            java.io.File(out, "classpath.txt").writeText(t.classpath.files.joinToString("\n"))
            java.io.File(out, "testdirs.txt").writeText(t.testClassesDirs.files.joinToString("\n"))
        }
    }
}
