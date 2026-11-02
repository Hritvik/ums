package com.vik.ums.recovery

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The last backup result, exported as age and failure only. */
class BackupSignals {
    @Volatile var lastSuccessAt: Instant? = null
    @Volatile var failed: Boolean = false
    @Volatile var age: Duration? = null
}

/**
 * Reads the status file the backup job writes. A missing or unreadable file is a failure, and a
 * successful backup older than 26 hours is an error, per the operating threshold.
 */
class BackupMonitor(
    private val statusFile: Path?,
    private val clock: Clock,
    private val signals: BackupSignals,
    private val maximumAge: Duration = Duration.ofHours(26),
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun check() {
        val path = statusFile ?: return
        if (!Files.isRegularFile(path)) return markFailed("Backup status file is missing")
        val status = try {
            mapper.readTree(path.toFile())
        } catch (unreadable: Exception) {
            return markFailed("Backup status file is unreadable")
        }
        val completedAt = status["completedAt"]?.asText()
        val failedNode = status["failed"]
        val failed = when {
            failedNode == null -> false
            failedNode.isBoolean -> failedNode.asBoolean()
            else -> true
        }
        if (failed) {
            markFailed("Backup reported a failure")
            signals.age = signals.lastSuccessAt?.let {
                val age = Duration.between(it, clock.instant())
                if (age.isNegative) Duration.ZERO else age
            }
            return
        }
        val completed = try {
            completedAt?.let { Instant.parse(it) }
        } catch (invalid: Exception) {
            null
        }
        if (completed == null) return markFailed("Backup status has no usable completion time")
        val age = Duration.between(completed, clock.instant())
        if (age.isNegative) return markFailed("Backup completion time is in the future")
        signals.lastSuccessAt = completed
        signals.age = age
        if (age > maximumAge) {
            signals.failed = true
            log.error("Last successful backup is {} old; the 26 hour threshold is exceeded", age)
        } else {
            signals.failed = false
        }
    }

    private fun markFailed(reason: String) {
        signals.failed = true
        signals.age = null
        log.error(reason)
    }

    private companion object {
        val log = LoggerFactory.getLogger(BackupMonitor::class.java)
    }
}
