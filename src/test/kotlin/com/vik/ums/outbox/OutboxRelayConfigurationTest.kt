package com.vik.ums.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.UUID

class OutboxRelayConfigurationTest {
    private val dataSource = DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/ums", "ums", "ums")
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val transport = object : OutboxTransport {
        override fun publish(event: OutboxEvent) = Unit
    }

    @Test fun `relay beans build from explicit configuration`() {
        val environment = MockEnvironment()
            .withProperty("ums.outbox.consumer-uri", "https://consumer.example.test/events")
            .withProperty("ums.outbox.repair.event-id", UUID.randomUUID().toString())
        val configuration = OutboxRelayConfiguration()

        val http = configuration.outboxTransport(environment, Clock.systemUTC())
        assertThat(http).isInstanceOf(HttpOutboxTransport::class.java)
        val relay = configuration.outboxRelay(jdbc, transactions, http, Clock.systemUTC(), environment)
        assertThat(configuration.outboxRelayScheduler(relay)).isNotNull()
        assertThat(configuration.outboxRepairRunner(relay, environment)).isNotNull()
        assertThat(configuration.outboxRelayMetrics(OutboxSignals(relay, Clock.systemUTC()))).isNotNull()
    }

    @Test fun `relay beans fall back to documented defaults`() {
        val environment = MockEnvironment()
            .withProperty("ums.outbox.consumer-uri", "http://consumer.example.test/events")
        val configuration = OutboxRelayConfiguration()

        val relay = configuration.outboxRelay(jdbc, transactions,
            configuration.outboxTransport(environment, Clock.systemUTC()), Clock.systemUTC(), environment)
        assertThat(relay).isNotNull()

        val invalid = MockEnvironment()
            .withProperty("ums.outbox.consumer-uri", "http://consumer.example.test/events")
            .withProperty("ums.outbox.relay.batch-size", "0")
        assertThatThrownBy {
            configuration.outboxRelay(jdbc, transactions, transport, Clock.systemUTC(), invalid)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `enabling the relay without a consumer endpoint fails fast`() {
        val context = relayContext(mapOf("ums.outbox.relay.enabled" to "true"))

        assertThatThrownBy { context.refresh() }
            .hasMessageContaining("ums.outbox.consumer-uri")
        context.close()
    }

    @Test fun `enabling the relay registers the delivery beans`() {
        val context = relayContext(mapOf(
            "ums.outbox.relay.enabled" to "true",
            "ums.outbox.consumer-uri" to "https://consumer.example.test/events",
        ))
        context.refresh()

        assertThat(context.getBean(OutboxRelay::class.java)).isNotNull()
        assertThat(context.getBean(OutboxRelayScheduler::class.java)).isNotNull()
        assertThat(context.getBean(OutboxSignals::class.java)).isNotNull()
        context.close()
    }

    @Test fun `a lease shorter than the transport timeout is refused`() {
        val environment = MockEnvironment()
            .withProperty("ums.outbox.consumer-uri", "https://consumer.example.test/events")
            .withProperty("ums.outbox.relay.lease", "PT1S")
            .withProperty("ums.outbox.relay.timeout-seconds", "30")

        assertThatThrownBy {
            OutboxRelayConfiguration().outboxRelay(jdbc, transactions, transport, Clock.systemUTC(), environment)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `a non-http consumer endpoint is refused`() {
        assertThatThrownBy { HttpOutboxTransport(URI.create("file:///tmp/events")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun relayContext(properties: Map<String, Any>): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("test", properties))
        context.registerBean("jdbc", JdbcTemplate::class.java, java.util.function.Supplier { jdbc })
        context.registerBean("transactions", TransactionTemplate::class.java,
            java.util.function.Supplier { transactions })
        context.registerBean("clock", Clock::class.java, java.util.function.Supplier { Clock.systemUTC() })
        context.register(OutboxRelayConfiguration::class.java)
        return context
    }

    @Test fun `relay settings must be positive`() {
        assertThatThrownBy { OutboxRelay(jdbc, transactions, transport, Clock.systemUTC(), batchSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OutboxRelay(jdbc, transactions, transport, Clock.systemUTC(), maxAttempts = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            OutboxRelay(jdbc, transactions, transport, Clock.systemUTC(), backoffBase = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            OutboxRelay(jdbc, transactions, transport, Clock.systemUTC(), backoffMax = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            OutboxRelay(jdbc, transactions, transport, Clock.systemUTC(), lease = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
