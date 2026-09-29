package dev.krispr.runtime

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * Loads the project's own classes and resources ([entries]: the instrumented main classes, the test
 * classes, other modules of the build) child-first, so each mutant a reused worker runs starts with fresh
 * static state. Everything else (the JDK, JUnit, libraries) comes from [parent] and stays loaded, and
 * JIT-compiled, across mutants. The krispr runtime always comes from [parent]: the worker sets the
 * active mutant on that copy.
 *
 * The parent is the system class loader, which also sees [entries]. Code that asks it directly (a library
 * calling `Class.forName` from its own class) gets the shared copies: still mutated, since they read the
 * same switch, but not reset between mutants.
 */
internal class IsolatingClassLoader(entries: List<File>, parent: ClassLoader) :
    URLClassLoader(entries.map { it.toURI().toURL() }.toTypedArray(), parent) {

    /** URL prefixes of resources the parent would also find in [entries]; filtered out to avoid duplicates. */
    private val prefixes = urLs.map { url -> if (url.path.endsWith("/")) url.toString() else "jar:$url!/" }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        if (name.startsWith("java.") || name.startsWith(RUNTIME_PACKAGE)) return super.loadClass(name, resolve)
        val loaded = findLoadedClass(name)
            ?: try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                null
            }
            ?: return super.loadClass(name, resolve)
        if (resolve) resolveClass(loaded)
        loaded
    }

    override fun getResource(name: String): URL? = findResource(name) ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val own = Collections.list(findResources(name))
        val inherited = Collections.list(parent.getResources(name)).filter { url -> prefixes.none { url.toString().startsWith(it) } }
        return Collections.enumeration(own + inherited)
    }

    private companion object {
        const val RUNTIME_PACKAGE = "dev.krispr.runtime."
    }
}
