package com.vik.ums.retention

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {
    @Bean
    fun retentionSweeper(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                         clock: java.time.Clock, environment: Environment): RetentionSweeper =
        RetentionSweeper(jdbc, transactions, clock,
            environment.getProperty("ums.retention.sweep.batch-size", "100").toInt())

    @Bean
    fun retentionSignals(): RetentionSignals = RetentionSignals()

    /** Counts only: a failing sweep is visible without exposing a record or a subject. */
    @Bean
    fun retentionMetrics(signals: RetentionSignals): MeterBinder = MeterBinder { registry ->
        Gauge.builder("ums.retention.swept", signals) { it.report?.swept?.toDouble() ?: Double.NaN }
            .register(registry)
        Gauge.builder("ums.retention.retained", signals) { it.report?.retained?.toDouble() ?: Double.NaN }
            .register(registry)
        Gauge.builder("ums.retention.failed", signals) { it.report?.failed?.toDouble() ?: Double.NaN }
            .register(registry)
    }
}

/** Last completed sweep, published for metrics. */
class RetentionSignals {
    @Volatile var report: RetentionReport? = null
}

/**
 * Retention is a promise with a clock, so the sweep runs on its own schedule. It logs counts
 * only: no identifier, subject, or payload value ever reaches a log line.
 */
@Component
@ConditionalOnProperty(name = ["ums.retention.sweep.enabled"], havingValue = "true", matchIfMissing = true)
class RetentionSweepScheduler(
    private val sweeper: RetentionSweeper,
    private val signals: RetentionSignals = RetentionSignals(),
) {
    @Scheduled(
        fixedDelayString = "\${ums.retention.sweep.interval:PT1H}",
        initialDelayString = "\${ums.retention.sweep.initial-delay:PT5M}",
    )
    fun sweep() {
        val report = sweeper.sweepAll()
        signals.report = report
        if (report.swept > 0 || report.failed > 0) {
            log.info("Retention sweep removed {} records, retained {}, failed {}",
                report.swept, report.retained, report.failed)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RetentionSweepScheduler::class.java)
    }
}
