package com.vik.ums.content

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileScope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.UUID

@Testcontainers
class HttpContentAuthorityTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine")
        private const val REGISTRATION = "dutch-development"
        private const val SUBJECT = "aps_0123456789abcdefghijkL"
    }

    private val mapper = jacksonObjectMapper()
    private val issuer = UUID.randomUUID()
    private val app = UUID.randomUUID()
    private val scope = ProfileScope(issuer, app, UUID.randomUUID())
    private lateinit var jdbc: JdbcTemplate
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var server: HttpServer
    private var reply: (String) -> Pair<Int, String> = { 500 to "" }
    private var seenAuthorization: String? = null
    private var seenBody: String? = null

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        dataSource = ds
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        val transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(AppScope(issuer, app), true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        jdbc.update("insert into ums_iam_source_bindings (issuer_environment_ref, environment) values (?, 'test')", issuer)
        jdbc.update("""insert into ums_iam_app_mappings (issuer_environment_ref, app_registration_id, app_reference)
            values (?, ?, ?)""", issuer, REGISTRATION, app)
        jdbc.update("""insert into ums_iam_subject_mappings
            (issuer_environment_ref, app_reference, app_subject, subject_reference, activation_id)
            values (?, ?, ?, ?, 'act_0123456789abcdef')""", issuer, app, SUBJECT, scope.subjectReference)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterEach fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
        seenBody = exchange.requestBody.readBytes().decodeToString()
        val (status, body) = reply(exchange.requestURI.path)
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun authority(token: String? = null) = HttpContentAuthority(
        URI.create("http://127.0.0.1:${server.address.port}/"), mapper, jdbc,
        Duration.ofSeconds(5), bearerToken = token)

    private fun ownership(body: String, status: Int = 200) {
        reply = { status to body }
    }

    @Test fun `an accepted ownership check requires the same app and subject`() {
        ownership("""{"valid":true,"state":"READY","appRegistrationId":"$REGISTRATION","appSubject":"$SUBJECT"}""")

        assertThat(authority("secret-token").check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.VALID)
        assertThat(seenAuthorization).isEqualTo("Bearer secret-token")
        assertThat(seenBody).contains("\"reference\":\"asset_0123456789abcdef\"")
            .contains("\"appRegistrationId\":\"$REGISTRATION\"")
            .contains("\"appSubject\":\"$SUBJECT\"")
    }

    @Test fun `revoked, invalid, foreign, and unknown owner answers are rejected`() {
        ownership("""{"valid":false,"state":"REVOKED"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.REVOKED)

        ownership("""{"valid":false,"state":"READY"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.REVOKED)

        ownership("""{"valid":true,"state":"READY","appRegistrationId":"other","appSubject":"$SUBJECT"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.OWNER_MISMATCH)

        ownership("""{"valid":true,"state":"PENDING","appRegistrationId":"$REGISTRATION","appSubject":"$SUBJECT"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.UNAVAILABLE)

        ownership("""{"state":"READY"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.REVOKED)

        // An outage is never a revocation, even when the owner also reports valid:false.
        ownership("""{"valid":false,"state":"UNAVAILABLE"}""")
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.UNAVAILABLE)
    }

    @Test fun `an unavailable or failing authority never authorizes anything`() {
        ownership("", status = 503)
        assertThat(authority().check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.UNAVAILABLE)

        val unmapped = ProfileScope(issuer, app, UUID.randomUUID())
        ownership("""{"valid":true,"state":"READY","appRegistrationId":"$REGISTRATION","appSubject":"$SUBJECT"}""")
        assertThat(authority().check("asset_0123456789abcdef", unmapped)).isEqualTo(ContentVerdict.UNAVAILABLE)
        assertThat(authority().requestCleanup("asset_0123456789abcdef", unmapped)).isFalse()

        val refused = HttpContentAuthority(
            URI.create("http://127.0.0.1:${freePort()}/"), mapper, jdbc, Duration.ofMillis(300))
        assertThat(refused.check("asset_0123456789abcdef", scope)).isEqualTo(ContentVerdict.UNAVAILABLE)
        assertThat(refused.requestCleanup("asset_0123456789abcdef", scope)).isFalse()
    }

    @Test fun `cleanup requires an explicit confirmation, not an acknowledgement`() {
        ownership("""{"accepted":true}""", status = 202)
        assertThat(authority().requestCleanup("asset_0123456789abcdef", scope)).isFalse()

        ownership("""{"confirmed":false}""")
        assertThat(authority().requestCleanup("asset_0123456789abcdef", scope)).isFalse()

        ownership("""{"confirmed":true}""")
        assertThat(authority().requestCleanup("asset_0123456789abcdef", scope)).isTrue()
        assertThat(seenBody).contains("asset_0123456789abcdef")

        ownership("", status = 409)
        assertThat(authority().requestCleanup("asset_0123456789abcdef", scope)).isFalse()
    }

    @Test fun `only http endpoints can be configured`() {
        assertThatThrownBy {
            HttpContentAuthority(URI.create("ftp://content.example.test/"), mapper, jdbc)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `configuration fails closed without a content endpoint`() {
        val configuration = ContentConfiguration()
        assertThat(configuration.contentAuthority(jdbc, mapper, MockEnvironment()))
            .isInstanceOf(UnconfiguredContentAuthority::class.java)
        assertThat(configuration.contentAuthority(jdbc, mapper, MockEnvironment().withProperty(
            "ums.content.authority-uri", "http://127.0.0.1:${server.address.port}/")))
            .isInstanceOf(HttpContentAuthority::class.java)
    }

    @Test fun `the cleanup worker and scheduler report confirmed work and remaining debt`() {
        val now = java.time.Instant.now()
        fun fence(id: UUID, state: String) = jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             retired_profile_reference, retired_generation, obligation_at, erased_binding_revision, erased_at,
             content_obligation, content_state, content_reference)
            values (?, ?, ?, ?, 'APP_DELETION', ?, 1, now(), 1, now(), true, ?, 'asset_0123456789abcdef')""",
            id, issuer, app, scope.subjectReference, UUID.randomUUID(), state)
        fence(UUID.randomUUID(), "PENDING")
        fence(UUID.randomUUID(), "FAILED")

        val configuration = ContentConfiguration()
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val worker = configuration.contentCleanupWorker(jdbc, StubAuthority(true), transactions, mapper,
            MockEnvironment().withProperty("ums.environment", "test"), java.time.Clock.systemUTC())
        assertThat(worker.run()).isEqualTo(1)
        assertThat(configuration.contentCleanupWorker(jdbc, StubAuthority(false), transactions, mapper,
            MockEnvironment().withProperty("ums.environment", "test")
                .withProperty("ums.content.cleanup.max-attempts", "3"), java.time.Clock.systemUTC())).isNotNull()
        configuration.contentCleanupScheduler(worker).run()

        val registry = SimpleMeterRegistry()
        configuration.contentMetrics(jdbc).bindTo(registry)
        assertThat(registry.get("ums.content.cleanup.pending").gauge().value()).isEqualTo(0.0)
        assertThat(registry.get("ums.content.cleanup.failed").gauge().value()).isEqualTo(1.0)
    }

    private class StubAuthority(private val confirms: Boolean) : ContentAuthority {
        override fun check(reference: String, scope: ProfileScope) = ContentVerdict.VALID
        override fun requestCleanup(reference: String, scope: ProfileScope) = confirms
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
