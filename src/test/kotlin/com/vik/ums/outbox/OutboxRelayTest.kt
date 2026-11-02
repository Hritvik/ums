package com.vik.ums.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
class OutboxRelayTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var transport: RecordingTransport
    private var signals: OutboxSignals? = null

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("truncate ums_event_outbox")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        transport = RecordingTransport()
    }

    private fun relay(batchSize: Int = 20, maxAttempts: Int = 3, base: Duration = Duration.ofSeconds(30),
                      max: Duration = Duration.ofHours(1), lease: Duration = Duration.ofMinutes(2)) =
        OutboxRelay(jdbc, transactions, transport, clock, batchSize, maxAttempts, base, max, lease)

    private fun emit(stream: String, revision: Long, at: Instant = clock.instant()): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
            values (?, 'app', ?, ?, 'ums.profile.state-changed.v1', ?::jsonb, ?)""",
            id, stream, revision, """{"eventId":"$id","schemaVersion":"1","producer":"ums"}""", java.sql.Timestamp.from(at))
        return id
    }

    private fun publishedAt(id: UUID): Instant? =
        jdbc.queryForObject("select published_at from ums_event_outbox where event_id = ?",
            { rs, _ -> rs.getTimestamp(1)?.toInstant() }, id)

    @Test fun `facts for one stream publish in revision order, one pass at a time`() {
        val first = emit("s1", 1)
        val second = emit("s1", 2)
        val third = emit("s1", 3)
        val relay = relay()

        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))

        assertThat(transport.published).containsExactly(first, second, third)
        assertThat(publishedAt(first)).isNotNull()
    }

    @Test fun `a failed head retries with backoff and keeps its place`() {
        val first = emit("s1", 1)
        val second = emit("s1", 2)
        transport.failures += OutboxFailureClass.TRANSPORT_UNAVAILABLE
        val relay = relay()

        assertThat(relay.relayOnce()).isEqualTo(RelayPass(0, 1, 0))
        assertThat(relay.status().retryingCount).isEqualTo(1)
        assertThat(relay.status().oldestUnpublished).isEqualTo(Duration.ZERO)
        assertThat(jdbc.queryForObject("select failure_class from ums_event_outbox where event_id = ?",
            String::class.java, first)).isEqualTo("TRANSPORT_UNAVAILABLE")
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(0, 0, 0))

        clock.advance(Duration.ofSeconds(30))
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))
        assertThat(transport.published).containsExactly(first, second)
    }

    @Test fun `repeated failures quarantine a stream without blocking another`() {
        val stuck = emit("s1", 1)
        val healthy = emit("s2", 1)
        val later = emit("s1", 2)
        transport.failingStreams += "s1"
        val relay = relay(maxAttempts = 2)

        repeat(2) {
            clock.advance(Duration.ofHours(2))
            relay.relayOnce()
        }

        assertThat(relay.status().quarantinedCount).isEqualTo(1)
        assertThat(relay.status().blockedStreamCount).isEqualTo(1)
        assertThat(relay.status().oldestQuarantine).isNotNull()
        assertThat(transport.published).containsExactly(healthy)
        assertThat(publishedAt(later)).isNull()
        assertThat(jdbc.queryForObject("select quarantined_at is not null from ums_event_outbox where event_id = ?",
            Boolean::class.java, stuck)).isTrue()

        // A stream that only becomes due after the quarantine still flows on the next pass.
        emit("s3", 1)
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))
    }

    @Test fun `a quarantined fact can be re-driven with the same identity`() {
        val stuck = emit("s1", 1)
        transport.failingStreams += "s1"
        val relay = relay(maxAttempts = 1)
        relay.relayOnce()
        assertThat(relay.status().quarantinedCount).isEqualTo(1)

        transport.failingStreams.clear()
        assertThat(relay.requeue(stuck)).isTrue()
        assertThat(relay.requeue(stuck)).isFalse()
        assertThat(relay.relayOnce()).isEqualTo(RelayPass(1, 0, 0))

        assertThat(transport.published).containsExactly(stuck)
        assertThat(relay.status().quarantinedCount).isZero()
    }

    @Test fun `a crash after the transport accepted replays a harmless duplicate`() {
        val event = emit("s1", 1)
        val crashing = object : OutboxTransport {
            override fun publish(event: OutboxEvent) {
                transport.published += event.eventId
                throw AssertionError("crash between publish and mark")
            }
        }
        val crashingRelay = OutboxRelay(jdbc, transactions, crashing, clock)

        assertThatThrownBy { crashingRelay.relayOnce() }.isInstanceOf(AssertionError::class.java)
        assertThat(publishedAt(event)).isNull()
        // The claim lease holds the fact back instead of retrying it immediately.
        assertThat(relay().relayOnce()).isEqualTo(RelayPass(0, 0, 0))

        clock.advance(Duration.ofMinutes(3))
        assertThat(relay().relayOnce()).isEqualTo(RelayPass(1, 0, 0))
        assertThat(transport.published).containsExactly(event, event)
        assertThat(publishedAt(event)).isNotNull()
    }

    @Test fun `only the due and unpublished rows are claimed`() {
        emit("s1", 1)
        val relay = relay()
        relay.relayOnce()

        assertThat(relay.relayOnce()).isEqualTo(RelayPass(0, 0, 0))
        assertThat(relay.status().unpublishedCount).isZero()
        assertThat(relay.status().oldestUnpublished).isNull()
    }

    @Test fun `transport statuses map to safe failure classes`() {
        assertThat(HttpOutboxTransport.failureClassFor(200)).isNull()
        assertThat(HttpOutboxTransport.failureClassFor(204)).isNull()
        assertThat(HttpOutboxTransport.failureClassFor(408)).isEqualTo(OutboxFailureClass.TRANSPORT_UNAVAILABLE)
        assertThat(HttpOutboxTransport.failureClassFor(429)).isEqualTo(OutboxFailureClass.TRANSPORT_UNAVAILABLE)
        assertThat(HttpOutboxTransport.failureClassFor(503)).isEqualTo(OutboxFailureClass.TRANSPORT_UNAVAILABLE)
        assertThat(HttpOutboxTransport.failureClassFor(400)).isEqualTo(OutboxFailureClass.TRANSPORT_REJECTED)
        assertThat(HttpOutboxTransport.failureClassFor(404)).isEqualTo(OutboxFailureClass.TRANSPORT_REJECTED)
    }

    @Test fun `the http transport posts the stored envelope and classifies refusals`() {
        var status = 202
        var body = ""
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            body = exchange.requestBody.readAllBytes().decodeToString()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val transport = HttpOutboxTransport(URI.create("http://127.0.0.1:${server.address.port}/events"))
            val event = OutboxEvent(UUID.randomUUID(), "app", "s1", 1, "ums.profile.state-changed.v1",
                """{"eventId":"x"}""")

            transport.publish(event)
            assertThat(body).isEqualTo(event.payload)

            status = 503
            assertThatThrownBy { transport.publish(event) }
                .isInstanceOfSatisfying(OutboxTransportException::class.java) {
                    assertThat(it.failureClass).isEqualTo(OutboxFailureClass.TRANSPORT_UNAVAILABLE)
                }

            status = 400
            assertThatThrownBy { transport.publish(event) }
                .isInstanceOfSatisfying(OutboxTransportException::class.java) {
                    assertThat(it.failureClass).isEqualTo(OutboxFailureClass.TRANSPORT_REJECTED)
                }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `an unreachable consumer is classified as unavailable`() {
        val transport = HttpOutboxTransport(URI.create("http://127.0.0.1:1/events"), Duration.ofMillis(200))

        assertThatThrownBy {
            transport.publish(OutboxEvent(UUID.randomUUID(), "app", "s1", 1, "t", "{}"))
        }.isInstanceOfSatisfying(OutboxTransportException::class.java) {
            assertThat(it.failureClass).isEqualTo(OutboxFailureClass.TRANSPORT_UNAVAILABLE)
        }
    }

    @Test fun `the scheduler relays and the repair runner re-drives`() {
        val event = emit("s1", 1)
        transport.failingStreams += "s1"
        val relay = relay(maxAttempts = 1)
        OutboxRelayScheduler(relay).relay()
        assertThat(relay.status().quarantinedCount).isEqualTo(1)

        transport.failingStreams.clear()
        OutboxRepairRunner(relay, event).run(org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>()))
        OutboxRelayScheduler(relay).relay()
        assertThat(transport.published).containsExactly(event)
    }

    @Test fun `delivery signals are exported as bounded gauges`() {
        emit("s1", 1)
        transport.failingStreams += "s1"
        val relay = relay(maxAttempts = 1)
        relay.relayOnce()

        signals = OutboxSignals(relay, clock)
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        OutboxRelayConfiguration().outboxRelayMetrics(signals!!).bindTo(registry)

        assertThat(registry.get("ums.outbox.quarantined").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("ums.outbox.blocked.streams").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("ums.outbox.unpublished").gauge().value()).isEqualTo(0.0)
        assertThat(registry.get("ums.outbox.oldest.quarantine.seconds").gauge().value()).isGreaterThanOrEqualTo(0.0)
    }

    @Test fun `a relay that keeps dying is quarantined instead of retried forever`() {
        val event = emit("s1", 1)
        val crashing = object : OutboxTransport {
            override fun publish(event: OutboxEvent) = throw AssertionError("crash before publish")
        }
        val relay = OutboxRelay(jdbc, transactions, crashing, clock, maxAttempts = 2)
        repeat(2) {
            assertThatThrownBy { relay.relayOnce() }.isInstanceOf(AssertionError::class.java)
            clock.advance(Duration.ofMinutes(3))
        }

        assertThat(relay.relayOnce()).isEqualTo(RelayPass(0, 0, 1))
        assertThat(jdbc.queryForObject("select failure_class from ums_event_outbox where event_id = ?",
            String::class.java, event)).isEqualTo("RELAY_INTERRUPTED")
        assertThat(publishedAt(event)).isNull()
        assertThat(relay.status().quarantinedCount).isEqualTo(1)
    }

    @Test fun `signal snapshots are reused for one window and survive a failed refresh`() {
        emit("s1", 1)
        val relay = relay()
        signals = OutboxSignals(relay, clock)

        assertThat(signals!!.current()?.unpublishedCount).isEqualTo(1)
        relay.relayOnce()
        assertThat(signals!!.current()?.unpublishedCount).isEqualTo(1)

        clock.advance(Duration.ofSeconds(6))
        assertThat(signals!!.current()?.unpublishedCount).isZero()

        // A database that disappears after the first snapshot keeps serving the last one.
        val pool = com.zaxxer.hikari.HikariDataSource().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        }
        val fragile = OutboxSignals(OutboxRelay(JdbcTemplate(pool), transactions, transport, clock), clock)
        assertThat(fragile.current()).isNotNull()
        pool.close()
        clock.advance(Duration.ofSeconds(6))
        assertThat(fragile.current()?.unpublishedCount).isEqualTo(0)
    }

    @Test fun `signals are unknown before the first snapshot`() {
        val pool = com.zaxxer.hikari.HikariDataSource().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        pool.close()
        signals = OutboxSignals(OutboxRelay(JdbcTemplate(pool), transactions, transport, clock), clock)

        assertThat(signals!!.current()).isNull()
    }

    private class RecordingTransport : OutboxTransport {
        val published = mutableListOf<UUID>()
        val failures = mutableListOf<OutboxFailureClass>()
        val failingStreams = mutableSetOf<String>()

        override fun publish(event: OutboxEvent) {
            if (event.streamId in failingStreams) {
                throw OutboxTransportException(OutboxFailureClass.TRANSPORT_REJECTED)
            }
            if (failures.isNotEmpty()) throw OutboxTransportException(failures.removeAt(0))
            published += event.eventId
        }
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
