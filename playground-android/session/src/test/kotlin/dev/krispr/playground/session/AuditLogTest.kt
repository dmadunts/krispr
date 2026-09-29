package dev.krispr.playground.session

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JUnit. The logger belongs to the JDK, which a reused JVM does not reload, so a second run finds
// the first run's handler still attached.
class AuditLogTest {
    @Test
    fun recordsLoginsOnce() {
        AuditLog.attach()
        assertEquals(1, AuditLog.handlerCount())
        AuditLog.login("ann", ok = true)
        AuditLog.login("bob", ok = false)
        AuditLog.debug("ignored")
        assertEquals(listOf("login ann", "failed login bob"), AuditLog.events)
    }
}
