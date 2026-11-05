package com.vik.ums.content

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class ContentConfiguration {
    @Bean
    fun contentCleanupWorker(jdbc: JdbcTemplate, authority: ContentAuthority,
                             transactions: org.springframework.transaction.support.TransactionTemplate,
                             mapper: ObjectMapper, environment: Environment,
                             clock: java.time.Clock): ContentCleanupWorker =
        ContentCleanupWorker(jdbc, authority,
            environment.getProperty("ums.content.cleanup.max-attempts", "8").toInt(),
            ContentAssociationRepair(jdbc, transactions, mapper,
                environment.getRequiredProperty("ums.environment"), authority, clock)::run)

    @Bean
    @ConditionalOnProperty(name = ["ums.content.authority-uri"])
    fun contentCleanupScheduler(worker: ContentCleanupWorker): ContentCleanupScheduler =
        ContentCleanupScheduler(worker)

    @Bean
    fun contentMetrics(jdbc: JdbcTemplate): io.micrometer.core.instrument.binder.MeterBinder =
        io.micrometer.core.instrument.binder.MeterBinder { registry ->
            io.micrometer.core.instrument.Gauge.builder("ums.content.cleanup.pending", jdbc) {
                it.queryForObject("""select (select count(*) from ums_profile_erasure_obligations
                        where content_state = 'PENDING')
                    + (select count(*) from ums_content_cleanup_requests where content_state = 'PENDING')""",
                    Long::class.java)?.toDouble() ?: 0.0
            }.register(registry)
            io.micrometer.core.instrument.Gauge.builder("ums.content.cleanup.failed", jdbc) {
                it.queryForObject("""select (select count(*) from ums_profile_erasure_obligations
                        where content_state = 'FAILED')
                    + (select count(*) from ums_content_cleanup_requests where content_state = 'FAILED')""",
                    Long::class.java)?.toDouble() ?: 0.0
            }.register(registry)
        }

    @Bean
    fun contentAuthority(jdbc: JdbcTemplate, mapper: ObjectMapper, environment: Environment): ContentAuthority {
        val uri = environment.getProperty("ums.content.authority-uri")
        return if (uri.isNullOrBlank()) UnconfiguredContentAuthority()
        else HttpContentAuthority(
            URI.create(uri), mapper, jdbc,
            Duration.ofSeconds(environment.getProperty("ums.content.timeout-seconds", "5").toLong()),
            bearerToken = environment.getProperty("ums.content.token"),
        )
    }
}

/**
 * Retries cleanup for erasures that dropped an avatar association and for global finals still
 * blocked by one; a confirmed (or exhausted) debt releases the block so a redelivered final can
 * erase with the debt visible.
 */
class ContentCleanupWorker(
    private val jdbc: JdbcTemplate,
    private val authority: ContentAuthority,
    private val maxAttempts: Int = 8,
    private val repair: (() -> Int)? = null,
) {
    /** Confirms cleanup for every pending debt, including a global final still blocked by one. */
    fun run(limit: Int = 50): Int {
        val pending = jdbc.query("""select fence_reference, issuer_environment_ref, app_reference,
                subject_reference, content_reference, content_attempts
            from ums_profile_erasure_obligations
            where content_state = 'PENDING' and content_reference is not null
            order by erased_at, fence_reference limit ?""",
            { rs, _ -> Pending(
                rs.getObject(1, java.util.UUID::class.java),
                com.vik.ums.profiles.ProfileScope(rs.getObject(2, java.util.UUID::class.java),
                    rs.getObject(3, java.util.UUID::class.java), rs.getObject(4, java.util.UUID::class.java)),
                rs.getString(5), rs.getInt(6)) }, limit)
        var confirmed = 0
        for (entry in pending) {
            if (authority.requestCleanup(entry.reference, entry.scope)) {
                jdbc.update("""update ums_profile_erasure_obligations set content_state = 'CONFIRMED',
                    content_failure_class = null where fence_reference = ?""", entry.fence)
                confirmed++
            } else {
                val attempts = entry.attempts + 1
                jdbc.update("""update ums_profile_erasure_obligations set content_attempts = ?,
                    content_failure_class = ?, content_state = ? where fence_reference = ?""",
                    attempts, "CONTENT_UNAVAILABLE", if (attempts >= maxAttempts) "FAILED" else "PENDING",
                    entry.fence)
            }
        }
        return confirmed + confirmClearedAvatars(limit) + (repair?.invoke() ?: 0)
    }

    /** Confirms cleanup for avatars dropped by an explicit clear or a replacement. */
    private fun confirmClearedAvatars(limit: Int): Int {
        val pending = jdbc.query("""select request_reference, issuer_environment_ref, app_reference,
                subject_reference, content_reference, content_attempts
            from ums_content_cleanup_requests
            where content_state = 'PENDING'
            order by requested_at, request_reference limit ?""",
            { rs, _ -> Pending(
                rs.getObject(1, java.util.UUID::class.java),
                com.vik.ums.profiles.ProfileScope(rs.getObject(2, java.util.UUID::class.java),
                    rs.getObject(3, java.util.UUID::class.java), rs.getObject(4, java.util.UUID::class.java)),
                rs.getString(5), rs.getInt(6)) }, limit)
        var confirmed = 0
        for (entry in pending) {
            if (authority.requestCleanup(entry.reference, entry.scope)) {
                jdbc.update("""update ums_content_cleanup_requests set content_state = 'CONFIRMED',
                    content_failure_class = null, confirmed_at = current_timestamp
                    where request_reference = ?""", entry.fence)
                confirmed++
            } else {
                val attempts = entry.attempts + 1
                jdbc.update("""update ums_content_cleanup_requests set content_attempts = ?,
                    content_failure_class = ?, content_state = ? where request_reference = ?""",
                    attempts, "CONTENT_UNAVAILABLE", if (attempts >= maxAttempts) "FAILED" else "PENDING",
                    entry.fence)
            }
        }
        return confirmed
    }

    private data class Pending(val fence: java.util.UUID, val scope: com.vik.ums.profiles.ProfileScope,
                               val reference: String, val attempts: Int)
}

class ContentCleanupScheduler(private val worker: ContentCleanupWorker) {
    @Scheduled(fixedDelayString = "\${ums.content.cleanup.interval:PT5M}",
        initialDelayString = "\${ums.content.cleanup.initial-delay:PT1M}")
    fun run() {
        val confirmed = worker.run()
        if (confirmed > 0) log.info("Content cleanup confirmed {} obligations", confirmed)
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(ContentCleanupScheduler::class.java)
    }
}
