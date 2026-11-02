package com.vik.ums.recovery

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class RecoveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun erasureJournal(environment: Environment): ErasureJournal =
        ErasureJournal(Path.of(environment.getRequiredProperty("ums.recovery.erasure-journal-path")))

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun erasureFeed(jdbc: JdbcTemplate): ErasureFeed = ErasureFeed(jdbc)

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun erasureJournalFlusher(feed: ErasureFeed, journal: ErasureJournal, environment: Environment): ErasureJournalFlusher =
        ErasureJournalFlusher(feed, journal,
            environment.getProperty("ums.recovery.journal-flush.batch-size", "100").toInt())

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun restoreReconciler(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                          journal: ErasureJournal, clock: Clock): RestoreReconciler =
        RestoreReconciler(jdbc, transactions, journal, clock)

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun restoreReconciliationLifecycle(journal: ErasureJournal, flusher: ErasureJournalFlusher,
                                       reconciler: RestoreReconciler,
                                       environment: Environment): SmartLifecycle =
        RestoreReconciliationLifecycle(journal, flusher, reconciler,
            environment.getProperty("ums.recovery.allow-missing-evidence", "false").toBoolean())

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun erasureJournalScheduler(flusher: ErasureJournalFlusher): ErasureJournalScheduler =
        ErasureJournalScheduler(flusher)

    @Bean
    fun backupSignals(): BackupSignals = BackupSignals()

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.backup-status-file"])
    fun backupMonitor(environment: Environment, clock: Clock, signals: BackupSignals): BackupMonitor =
        BackupMonitor(Path.of(environment.getRequiredProperty("ums.recovery.backup-status-file")), clock, signals)

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.backup-status-file"])
    fun backupMonitorScheduler(monitor: BackupMonitor): BackupMonitorScheduler = BackupMonitorScheduler(monitor)

    /** A deployment without the journal cannot prove a restore; say so once, loudly. */
    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"], matchIfMissing = true, havingValue = "__unset__")
    fun missingEvidenceWarning(): org.springframework.boot.ApplicationRunner = org.springframework.boot.ApplicationRunner {
        log.warn("No erasure journal is configured; restore evidence is disabled and a restored backup cannot be reconciled")
    }

    @Bean
    fun recoveryMetrics(signals: BackupSignals): MeterBinder = MeterBinder { registry ->
        Gauge.builder("ums.backup.age.seconds", signals) { it.age?.seconds?.toDouble() ?: Double.NaN }
            .register(registry)
        Gauge.builder("ums.backup.failed", signals) { if (it.lastSuccessAt == null && !it.failed) Double.NaN else if (it.failed) 1.0 else 0.0 }
            .register(registry)
    }
}

private val log = LoggerFactory.getLogger(RecoveryConfiguration::class.java)

/**
 * A lifecycle phase below the web server: reconciliation finishes before the port opens, and a
 * failure aborts startup instead of serving a restored database without evidence.
 */
class RestoreReconciliationLifecycle(
    private val journal: ErasureJournal,
    private val flusher: ErasureJournalFlusher,
    private val reconciler: RestoreReconciler,
    private val allowMissingEvidence: Boolean,
) : SmartLifecycle {
    @Volatile private var running = false

    override fun start() {
        if (!Files.exists(journal.path)) {
            if (!allowMissingEvidence) {
                throw IllegalStateException(
                    "Erasure journal is missing at ${journal.path}; refusing to serve without restore evidence")
            }
            log.error("Erasure journal is missing at {}; starting in degraded mode by configuration", journal.path)
        } else {
            val flushed = flusher.flushAll()
            check(!flusher.hasPending()) {
                "Erasure journal could not be brought up to date; refusing to serve with pending evidence"
            }
            val result = reconciler.reconcile()
            log.info("Restore reconciliation: {} entries, {} erased, {} already erased, {} newer generations, "
                + "{} missing, {} late journal appends",
                result.entries, result.erased, result.alreadyErased, result.newerGeneration, result.missing, flushed)
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    /** Below the web server's phase (Integer.MAX_VALUE - 1), so the socket opens last. */
    override fun getPhase(): Int = 0

    private companion object {
        val log = LoggerFactory.getLogger(RestoreReconciliationLifecycle::class.java)
    }
}

/** Retries pending journal appends between restarts. */
class ErasureJournalScheduler(private val flusher: ErasureJournalFlusher) {
    @Scheduled(
        fixedDelayString = "\${ums.recovery.journal-flush.interval:PT15S}",
        initialDelayString = "\${ums.recovery.journal-flush.initial-delay:PT10S}",
    )
    fun flush() {
        flusher.flush()
    }
}

class BackupMonitorScheduler(private val monitor: BackupMonitor) {
    @Scheduled(
        fixedDelayString = "\${ums.recovery.backup-check.interval:PT5M}",
        initialDelayString = "\${ums.recovery.backup-check.initial-delay:PT30S}",
    )
    fun check() {
        monitor.check()
    }
}
