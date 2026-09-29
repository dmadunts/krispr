package dev.krispr.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/** A second copy of [Mutants], as a Robolectric sandbox loads one, follows the system copy's switch. */
class MutantsTest {
    @AfterEach
    fun reset() {
        Mutants.activeId = Mutants.NONE
        Mutants.activated = false
    }

    private fun location(type: Class<*>) = File(type.protectionDomain.codeSource.location.toURI()).toURI().toURL()

    /** Loads the runtime and Kotlin apart from the system class loader, like a sandbox. */
    private fun sandbox() = URLClassLoader(arrayOf(location(Mutants::class.java), location(Unit::class.java)), ClassLoader.getPlatformClassLoader())

    @Test
    fun aSandboxCopyFollowsTheActiveMutantAndReportsReachingIt() {
        assertEquals(ClassLoader.getSystemClassLoader(), Mutants::class.java.classLoader, "the test needs the runtime on the system class path")
        Mutants.activeId = 7
        sandbox().use { loader ->
            val copy = loader.loadClass(Mutants::class.java.name)
            assertNotSame(Mutants::class.java, copy)
            fun activeId() = copy.getMethod("getActiveId").invoke(null) as Int
            fun isActive(id: Int) = copy.getMethod("isActive", Int::class.javaPrimitiveType).invoke(null, id) as Boolean
            assertEquals(7, activeId(), "a copy loaded mid-run takes the current mutant")

            assertTrue(isActive(7))
            assertTrue(Mutants.activated)

            // The next mutant, in the same sandbox: the copy switches, and forgets it reached the last one.
            Mutants.activated = false
            Mutants.activeId = 8
            assertEquals(8, activeId())
            assertFalse(isActive(7))
            assertFalse(Mutants.activated)
            assertTrue(isActive(8))
            assertTrue(Mutants.activated, "the copy reports reaching the new mutant too")

            Mutants.activeId = Mutants.NONE
            assertEquals(Mutants.NONE, activeId())
        }
    }
}
