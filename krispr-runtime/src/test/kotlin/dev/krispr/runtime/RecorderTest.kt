package dev.krispr.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

class RecorderTest {
    /** Loads the runtime's own classes again, child-first, the way Robolectric's sandbox does. */
    private class SandboxLoader(parent: ClassLoader) :
        URLClassLoader(arrayOf(Recorder::class.java.protectionDomain.codeSource.location), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("dev.krispr.runtime.")) return super.loadClass(name, resolve)
            findLoadedClass(name) ?: findClass(name)
        }
    }

    @Test
    fun hitsInASandboxedCopyReachTheRecorderTheListenerDrives() {
        val sandboxed = SandboxLoader(javaClass.classLoader).loadClass(Recorder::class.java.name)
        assertNotSame(Recorder::class.java, sandboxed)

        Recorder.enter("[engine:junit-vintage]/[runner:SomeRobolectricTest]", "SomeRobolectricTest")
        try {
            val copy = sandboxed.getField("INSTANCE").get(null)
            sandboxed.getMethod("hit", Int::class.javaPrimitiveType).invoke(copy, 4242)
        } finally {
            Recorder.exit()
        }
        assertEquals(setOf("[engine:junit-vintage]/[runner:SomeRobolectricTest]"), Recorder.testsReaching(4242))
    }
}
