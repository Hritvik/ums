package com.vik.ums.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.notices.ProfileNoticeService
import com.vik.ums.privacy.DueDeletionBatchService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.recovery.BackupSignals
import com.vik.ums.retention.RetentionSignals
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class OperationsConfiguration {
    @Bean
    fun authorizationFailures(): AuthorizationFailures = AuthorizationFailures()

    @Bean
    fun profileFinalizationService(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                                   mapper: ObjectMapper, environment: Environment, clock: Clock): ProfileFinalizationService =
        ProfileFinalizationService(jdbc, transactions, mapper,
            environment.getRequiredProperty("ums.environment"), clock)

    @Bean
    fun dueDeletionBatchService(jdbc: JdbcTemplate, finalizer: ProfileFinalizationService,
                                clock: Clock): DueDeletionBatchService =
        DueDeletionBatchService(jdbc, finalizer, clock)

    @Bean
    fun profileNoticeService(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                             mapper: ObjectMapper, environment: Environment, clock: Clock): ProfileNoticeService =
        ProfileNoticeService(jdbc, transactions, mapper,
            environment.getRequiredProperty("ums.environment"), clock)

    @Bean
    fun workerLease(jdbc: JdbcTemplate, clock: Clock): WorkerLease = WorkerLease(jdbc, clock)

    @Bean
    @ConditionalOnProperty(name = ["ums.maintenance.enabled"], havingValue = "true", matchIfMissing = true)
    fun maintenanceWorker(jdbc: JdbcTemplate, lease: WorkerLease, batches: DueDeletionBatchService,
                          notices: ProfileNoticeService, clock: Clock, environment: Environment): MaintenanceWorker =
        MaintenanceWorker(jdbc, lease, batches, notices, clock,
            batchLimit = environment.getProperty("ums.maintenance.batch-limit", "50").toInt(),
            maxPages = environment.getProperty("ums.maintenance.max-pages", "20").toInt(),
            noticeLimit = environment.getProperty("ums.maintenance.notice-limit", "100").toInt())

    @Bean
    fun operationalAlerts(jdbc: JdbcTemplate, clock: Clock, failures: AuthorizationFailures,
                          retention: RetentionSignals, backup: BackupSignals): OperationalAlerts =
        OperationalAlerts(jdbc, clock, failures, retention, backup)

    @Bean
    @ConditionalOnProperty(name = ["ums.maintenance.enabled"], havingValue = "true", matchIfMissing = true)
    fun maintenanceScheduler(worker: MaintenanceWorker): MaintenanceScheduler = MaintenanceScheduler(worker)

    @Bean
    fun alertScheduler(alerts: OperationalAlerts): AlertScheduler = AlertScheduler(alerts)

    @Bean
    fun operationsMetrics(alerts: OperationalAlerts): MeterBinder = MeterBinder { registry ->
        listOf(
            "overdue.erasures" to { s: AlertSnapshot -> s.overdueErasures.toDouble() },
            "overdue.beyond.objective" to { s: AlertSnapshot -> s.overdueBeyondObjective.toDouble() },
            "poisoned.outbox" to { s: AlertSnapshot -> s.poisonedOutbox.toDouble() },
            "ingestion.quarantine.open" to { s: AlertSnapshot -> s.openIngestionQuarantine.toDouble() },
            "failed.retention" to { s: AlertSnapshot -> s.failedRetentionSweeps.toDouble() },
            "authorization.failures" to { s: AlertSnapshot -> s.authorizationFailures.toDouble() },
        ).forEach { (name, value) ->
            Gauge.builder("ums.alert.$name", alerts) { value(it.snapshot()) }.register(registry)
        }
        Gauge.builder("ums.alert.backup.failed", alerts) {
            if (it.snapshot().backupFailed) 1.0 else 0.0
        }.register(registry)
    }
}

/** Runs the finalizer and notice obligations on a cadence, single-flight through the lease. */
class MaintenanceScheduler(private val worker: MaintenanceWorker) {
    @Scheduled(
        fixedDelayString = "\${ums.maintenance.interval:PT5M}",
        initialDelayString = "\${ums.maintenance.initial-delay:PT2M}",
    )
    fun run() {
        val result = worker.run()
        if (!result.skipped && result.finalized + result.blocked + result.retryable > 0) {
            log.info("Maintenance worker finalized {}, blocked {}, retryable {}, notices {}",
                result.finalized, result.blocked, result.retryable, result.noticesScheduled)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MaintenanceScheduler::class.java)
    }
}

class AlertScheduler(private val alerts: OperationalAlerts) {
    @Scheduled(
        fixedDelayString = "\${ums.maintenance.alert-interval:PT5M}",
        initialDelayString = "\${ums.maintenance.alert-initial-delay:PT2M}",
    )
    fun check() {
        alerts.check()
    }
}
