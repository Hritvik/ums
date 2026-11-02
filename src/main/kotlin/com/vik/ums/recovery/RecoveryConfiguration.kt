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
                          mapper: com.fasterxml.jackson.databind.ObjectMapper,
                          environment: Environment, journal: ErasureJournal, clock: Clock): RestoreReconciler =
        RestoreReconciler(jdbc, transactions, mapper,
            environment.getRequiredProperty("ums.environment"), journal, clock)

    @Bean
    @ConditionalOnProperty(name = ["ums.recovery.erasure-journal-path"])
    fun restoreReconciliationLifecycle(jdbc: JdbcTemplate, journal: ErasureJournal,
                                       flusher: ErasureJournalFlusher, reconciler: RestoreReconciler,
                                       environment: Environment): SmartLifecycle =
        RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler,
            environment.getProperty("ums.recovery.allow-missing-evidence", "false").toBoolean(),
            environment.getProperty("ums.recovery.bootstrap-journal", "false").toBoolean())

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
    private val jdbc: JdbcTemplate,
    private val journal: ErasureJournal,
    private val flusher: ErasureJournalFlusher,
    private val reconciler: RestoreReconciler,
    private val allowMissingEvidence: Boolean,
    private val bootstrapJournal: Boolean = false,
) : SmartLifecycle {
    @Volatile private var running = false

    override fun start() {
        if (!Files.exists(journal.path)) {
            when {
                bootstrapJournal -> {
                    // A first-ever start has no data to resurrect; anything else is a restore.
                    val bindings = jdbc.queryForObject("select count(*) from ums_profile_bindings",
                        Long::class.java) ?: 0L
                    check(bindings == 0L) {
                        "Refusing to bootstrap the erasure journal: the database already has profiles"
                    }
                    journal.initialise()
                    log.warn("Created a new erasure journal at {}", journal.path)
                }
                allowMissingEvidence -> log.error(
                    "Erasure journal is missing at {}; starting in degraded mode by configuration", journal.path)
                else -> throw IllegalStateException(
                    "Erasure journal is missing at ${journal.path}; refusing to serve without restore evidence")
            }
        }
        if (Files.exists(journal.path)) {
            // Checked before flushing: refilling a truncated journal must not mask lost evidence.
            check(reconciler.lastPosition() <= journal.entries().size) {
                "Erasure journal is shorter than the reconciliation cursor; evidence is missing"
            }
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
