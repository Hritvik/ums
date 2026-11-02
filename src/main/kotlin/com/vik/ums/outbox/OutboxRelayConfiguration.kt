package com.vik.ums.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * The relay is off unless a consumer endpoint is configured: without one every fact would only
 * accumulate retries. Counts and ages are exported as gauges; nothing about a subject is.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ums.outbox.relay.enabled"], havingValue = "true")
class OutboxRelayConfiguration {
    @Bean
    fun outboxTransport(environment: Environment): OutboxTransport =
        HttpOutboxTransport(
            URI.create(environment.getRequiredProperty("ums.outbox.consumer-uri")),
            Duration.ofSeconds(environment.getProperty("ums.outbox.relay.timeout-seconds", "5").toLong()),
        )

    @Bean
    fun outboxRelay(jdbc: JdbcTemplate, transactions: TransactionTemplate, transport: OutboxTransport,
                    clock: java.time.Clock, environment: Environment): OutboxRelay =
        OutboxRelay(
            jdbc, transactions, transport, clock,
            batchSize = environment.getProperty("ums.outbox.relay.batch-size", "20").toInt(),
            maxAttempts = environment.getProperty("ums.outbox.relay.max-attempts", "8").toInt(),
            backoffBase = Duration.parse(environment.getProperty("ums.outbox.relay.backoff-base", "PT30S")),
            backoffMax = Duration.parse(environment.getProperty("ums.outbox.relay.backoff-max", "PT1H")),
            lease = Duration.parse(environment.getProperty("ums.outbox.relay.lease", "PT2M")),
        )

    @Bean
    fun outboxRelayScheduler(relay: OutboxRelay): OutboxRelayScheduler = OutboxRelayScheduler(relay)

    @Bean
    @ConditionalOnProperty(name = ["ums.outbox.repair.event-id"])
    fun outboxRepairRunner(relay: OutboxRelay, environment: Environment): ApplicationRunner =
        OutboxRepairRunner(relay, UUID.fromString(environment.getRequiredProperty("ums.outbox.repair.event-id")))

    @Bean
    fun outboxRelayMetrics(relay: OutboxRelay): MeterBinder = MeterBinder { registry ->
        gauge("ums.outbox.unpublished", relay, registry) { it.unpublishedCount.toDouble() }
        gauge("ums.outbox.retrying", relay, registry) { it.retryingCount.toDouble() }
        gauge("ums.outbox.quarantined", relay, registry) { it.quarantinedCount.toDouble() }
        gauge("ums.outbox.blocked.streams", relay, registry) { it.blockedStreamCount.toDouble() }
        gauge("ums.outbox.oldest.unpublished.seconds", relay, registry) {
            it.oldestUnpublished?.seconds?.toDouble() ?: 0.0
        }
        gauge("ums.outbox.oldest.quarantine.seconds", relay, registry) {
            it.oldestQuarantine?.seconds?.toDouble() ?: 0.0
        }
    }

    private fun gauge(name: String, relay: OutboxRelay, registry: io.micrometer.core.instrument.MeterRegistry,
                      value: (OutboxStatus) -> Double) {
        Gauge.builder(name, relay) { value(it.status()) }.register(registry)
    }
}

/** One relay pass on a fixed delay; the pass itself is idempotent, so a long pass is harmless. */
class OutboxRelayScheduler(private val relay: OutboxRelay) {
    @Scheduled(
        fixedDelayString = "\${ums.outbox.relay.interval:PT30S}",
        initialDelayString = "\${ums.outbox.relay.initial-delay:PT10S}",
    )
    fun relay() {
        val pass = relay.relayOnce()
        if (pass.failed > 0 || pass.quarantined > 0) {
            log.warn("Outbox relay pass published {} and left {} failed, {} quarantined",
                pass.published, pass.failed, pass.quarantined)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(OutboxRelayScheduler::class.java)
    }
}

/** Startup repair path for one quarantined fact, run once with the documented property set. */
class OutboxRepairRunner(
    private val relay: OutboxRelay,
    private val eventId: UUID,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val requeued = relay.requeue(eventId)
        if (requeued) log.info("Outbox event {} returned to the queue", eventId)
        else log.warn("Outbox event {} is not quarantined; nothing to re-drive", eventId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(OutboxRepairRunner::class.java)
    }
}
