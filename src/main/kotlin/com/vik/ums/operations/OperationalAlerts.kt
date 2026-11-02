package com.vik.ums.operations

import com.vik.ums.privacy.ExportSweepSignals
import com.vik.ums.recovery.BackupSignals
import com.vik.ums.retention.RetentionSignals
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

/** Threshold results, exported as gauges and logged as safe counts. */
data class AlertSnapshot(
    val overdueErasures: Long,
    val overdueBeyondObjective: Long,
    val poisonedOutbox: Long,
    val openIngestionQuarantine: Long,
    val failedRetentionSweeps: Long,
    val failedExportCleanup: Long,
    val backupFailed: Boolean,
    val authorizationFailures: Long,
    val newAuthorizationFailures: Long = 0,
)

/** The last checked snapshot. Gauges read this, so a scrape never queries or mutates counters. */
class AlertSignals {
    @Volatile var snapshot: AlertSnapshot? = null
}

/**
 * The thresholds the operating design names. Alerts carry counts and failure classes only: no
 * profile value, reference, token, or download link is ever logged or labelled.
 */
class OperationalAlerts(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val failures: AuthorizationFailures,
    private val retention: RetentionSignals,
    private val backup: BackupSignals,
    private val exports: ExportSweepSignals,
    private val signals: AlertSignals,
    private val retentionEnabled: Boolean = true,
    private val objective: Duration = Duration.ofHours(24),
    private val failureBurst: Long = 5,
) {
    @Volatile private var observedFailures = 0L

    fun snapshot(): AlertSnapshot {
        val now = clock.instant()
        val overdue = count("""select count(*) from ums_profile_deletion_requests
            where status = 'PENDING' and deadline <= ?""", now)
        val beyond = count("""select count(*) from ums_profile_deletion_requests
            where status = 'PENDING' and deadline <= ?""", now.minus(objective))
        val poisoned = count("select count(*) from ums_event_outbox where quarantined_at is not null", null)
        val quarantine = count("select count(*) from ums_iam_ingestion_quarantine where state = 'OPEN'", null)
        // A disabled or never-completed sweep is a cleanup failure, not a healthy zero.
        val failed = retention.report?.failed?.toLong()
            ?: if (retentionEnabled && retention.completedAt == null) 1L else 0L
        val exportFailed = if (exports.failed) 1L else 0L
        val backupFailed = !backup.configured || backup.failed
        return AlertSnapshot(overdue, beyond, poisoned, quarantine, failed, exportFailed, backupFailed,
            failures.count())
    }

    fun check(): AlertSnapshot {
        val measured = snapshot()
        val delta = (measured.authorizationFailures - observedFailures).coerceAtLeast(0)
        observedFailures = measured.authorizationFailures
        val snapshot = measured.copy(newAuthorizationFailures = delta)
        signals.snapshot = snapshot
        if (snapshot.overdueErasures > 0) {
            log.error("{} erasure requests are past their deadline; {} are more than {} overdue",
                snapshot.overdueErasures, snapshot.overdueBeyondObjective, objective)
        }
        if (snapshot.poisonedOutbox > 0) log.error("{} outbox facts are quarantined", snapshot.poisonedOutbox)
        if (snapshot.openIngestionQuarantine > 0) {
            log.error("{} ingestion events are quarantined", snapshot.openIngestionQuarantine)
        }
        if (snapshot.failedRetentionSweeps > 0) {
            log.error("{} retention deletions failed or the sweep has not completed", snapshot.failedRetentionSweeps)
        }
        if (snapshot.failedExportCleanup > 0) log.error("Export cleanup failed on the last pass")
        if (snapshot.backupFailed) log.error("The last backup is failed, stale, or not configured")
        if (snapshot.newAuthorizationFailures >= failureBurst) {
            log.error("{} authorization failures since the last check", snapshot.newAuthorizationFailures)
        }
        return snapshot
    }

    private fun count(sql: String, argument: java.time.Instant?): Long =
        if (argument == null) jdbc.queryForObject(sql, Long::class.java) ?: 0L
        else jdbc.queryForObject(sql, Long::class.java, Timestamp.from(argument)) ?: 0L

    private companion object {
        val log = LoggerFactory.getLogger(OperationalAlerts::class.java)
    }
}
