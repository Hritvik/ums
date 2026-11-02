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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The relay is off unless a consumer endpoint is configured: without one every fact would only
 * accumulate retries. Counts and ages are exported as gauges; nothing about a subject is.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ums.outbox.relay.enabled"], havingValue = "true")
class OutboxRelayConfiguration {
    @Bean
    fun outboxTransport(environment: Environment, clock: Clock): OutboxTransport =
        HttpOutboxTransport(
            URI.create(environment.getRequiredProperty("ums.outbox.consumer-uri")),
            Duration.ofSeconds(environment.getProperty("ums.outbox.relay.timeout-seconds", "5").toLong()),
            bearerToken = environment.getProperty("ums.outbox.consumer-token"),
        )

    @Bean
    fun outboxRelay(jdbc: JdbcTemplate, transactions: TransactionTemplate, transport: OutboxTransport,
                    clock: Clock, environment: Environment): OutboxRelay {
        val timeout = Duration.ofSeconds(environment.getProperty("ums.outbox.relay.timeout-seconds", "5").toLong())
        val lease = Duration.parse(environment.getProperty("ums.outbox.relay.lease", "PT2M"))
        // A lease shorter than the transport timeout would let another instance re-claim a fact
        // while its publish is still in flight.
        require(lease > timeout) { "The relay lease must exceed the transport timeout" }
        return OutboxRelay(
            jdbc, transactions, transport, clock,
            batchSize = environment.getProperty("ums.outbox.relay.batch-size", "20").toInt(),
            maxAttempts = environment.getProperty("ums.outbox.relay.max-attempts", "8").toInt(),
            backoffBase = Duration.parse(environment.getProperty("ums.outbox.relay.backoff-base", "PT30S")),
            backoffMax = Duration.parse(environment.getProperty("ums.outbox.relay.backoff-max", "PT1H")),
            lease = lease,
        )
    }

    @Bean
    fun outboxSignals(relay: OutboxRelay, clock: Clock): OutboxSignals = OutboxSignals(relay, clock)

    @Bean
    fun outboxRelayScheduler(relay: OutboxRelay): OutboxRelayScheduler = OutboxRelayScheduler(relay)

    @Bean
    @ConditionalOnProperty(name = ["ums.outbox.repair.event-id"])
    fun outboxRepairRunner(relay: OutboxRelay, environment: Environment): ApplicationRunner =
        OutboxRepairRunner(relay, UUID.fromString(environment.getRequiredProperty("ums.outbox.repair.event-id")))

    @Bean
    fun outboxRelayMetrics(signals: OutboxSignals): MeterBinder = MeterBinder { registry ->
        gauge("ums.outbox.unpublished", signals, registry) { it.unpublishedCount.toDouble() }
        gauge("ums.outbox.retrying", signals, registry) { it.retryingCount.toDouble() }
        gauge("ums.outbox.quarantined", signals, registry) { it.quarantinedCount.toDouble() }
        gauge("ums.outbox.blocked.streams", signals, registry) { it.blockedStreamCount.toDouble() }
        gauge("ums.outbox.oldest.unpublished.seconds", signals, registry) {
            it.oldestUnpublished?.seconds?.toDouble() ?: 0.0
        }
        gauge("ums.outbox.oldest.quarantine.seconds", signals, registry) {
            it.oldestQuarantine?.seconds?.toDouble() ?: 0.0
        }
    }

    private fun gauge(name: String, signals: OutboxSignals, registry: io.micrometer.core.instrument.MeterRegistry,
                      value: (OutboxStatus) -> Double) {
        Gauge.builder(name, signals) { value(it.current()) }.register(registry)
    }
}

/**
 * One status snapshot per scrape window, so six gauges do not turn into twelve queries, and a
 * database hiccup cannot fail the whole metrics exposition.
 */
class OutboxSignals(
    private val relay: OutboxRelay,
    private val clock: Clock,
    private val window: Duration = Duration.ofSeconds(5),
) {
    @Volatile private var snapshot: OutboxStatus? = null
    @Volatile private var takenAt: Instant = Instant.EPOCH

    fun current(): OutboxStatus {
        val now = clock.instant()
        val cached = snapshot
        if (cached != null && Duration.between(takenAt, now) < window) return cached
        val fresh = runCatching { relay.status() }.getOrElse { cached ?: EMPTY }
        snapshot = fresh
        takenAt = now
        return fresh
    }

    private companion object {
        val EMPTY = OutboxStatus(0, null, 0, 0, null, 0)
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
