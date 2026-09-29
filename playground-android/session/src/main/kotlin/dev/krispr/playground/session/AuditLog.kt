package dev.krispr.playground.session

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Collects audit events through java.util.logging, whose loggers live in the JDK, not in the app. */
object AuditLog {
    private val logger: Logger = Logger.getLogger("playground.audit")
    val events = mutableListOf<String>()

    fun attach() {
        logger.addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.INFO.intValue()) events += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    fun handlerCount(): Int = logger.handlers.size

    fun login(user: String, ok: Boolean) {
        if (ok) logger.info("login $user") else logger.warning("failed login $user")
    }

    fun debug(message: String) = logger.fine(message)
}
