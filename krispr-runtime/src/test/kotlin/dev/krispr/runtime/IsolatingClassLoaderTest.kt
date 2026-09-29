package dev.krispr.runtime

import dev.krispr.fixture.StaticCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.File

class IsolatingClassLoaderTest {
    private val testClasses = File(StaticCounter::class.java.protectionDomain.codeSource.location.toURI())

    private fun increment(loader: ClassLoader): Int {
        val counter = loader.loadClass(StaticCounter::class.java.name)
        counter.getMethod("setCount", Int::class.javaPrimitiveType).invoke(null, counter.getMethod("getCount").invoke(null) as Int + 1)
        return counter.getMethod("getCount").invoke(null) as Int
    }

    @Test
    fun eachLoaderStartsWithFreshStaticState() {
        StaticCounter.count = 41
        IsolatingClassLoader(listOf(testClasses), javaClass.classLoader).use { first ->
            assertNotSame(StaticCounter::class.java, first.loadClass(StaticCounter::class.java.name))
            assertEquals(1, increment(first))
            assertEquals(2, increment(first))
        }
        IsolatingClassLoader(listOf(testClasses), javaClass.classLoader).use { second ->
            assertEquals(1, increment(second))
        }
        assertEquals(41, StaticCounter.count)
    }

    @Test
    fun theRuntimeAndTheJdkComeFromTheParent() {
        val runtimeClasses = File(Mutants::class.java.protectionDomain.codeSource.location.toURI())
        IsolatingClassLoader(listOf(testClasses, runtimeClasses), javaClass.classLoader).use { loader ->
            assertSame(Mutants::class.java, loader.loadClass(Mutants::class.java.name))
            assertSame(String::class.java, loader.loadClass("java.lang.String"))
        }
    }

    @Test
    fun resourcesInEntriesAreListedOnce() {
        val name = StaticCounter::class.java.name.replace('.', '/') + ".class"
        IsolatingClassLoader(listOf(testClasses), javaClass.classLoader).use { loader ->
            assertEquals(1, loader.getResources(name).toList().size)
        }
    }
}
