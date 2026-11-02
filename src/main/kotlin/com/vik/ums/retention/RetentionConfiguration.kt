package com.vik.ums.retention

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
}

/**
 * Retention is a promise with a clock, so the sweep runs on its own schedule. It logs counts
 * only: no identifier, subject, or payload value ever reaches a log line.
 */
@Component
@ConditionalOnProperty(name = ["ums.retention.sweep.enabled"], havingValue = "true", matchIfMissing = true)
class RetentionSweepScheduler(private val sweeper: RetentionSweeper) {
    @Scheduled(
        fixedDelayString = "\${ums.retention.sweep.interval:PT1H}",
        initialDelayString = "\${ums.retention.sweep.initial-delay:PT5M}",
    )
    fun sweep() {
        val report = sweeper.sweepAll()
        if (report.swept > 0 || report.failed > 0) {
            log.info("Retention sweep removed {} records, retained {}, failed {}",
                report.swept, report.retained, report.failed)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RetentionSweepScheduler::class.java)
    }
}
