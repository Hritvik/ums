package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileProvisioningService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The real adapter, ingestion, resolver and controller against PostgreSQL. */
@Testcontainers
class IamIngressIntegrationTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val issuer = "https://iam.example.test"
    private val audience = "https://ums.example.test/internal/iam/events"
    private val workload = "wl_0123456789abcdefghijkL"
    private lateinit var jdbc: JdbcTemplate
    private lateinit var controller: IamIngressController

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_iam_ingestion_quarantine, ums_profile_erasure_obligations,
            ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests,
            ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts,
            ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts,
            ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t1"))
        val adapter = IamOnboardingAdapter(jdbc, tx, ProfileProvisioningService(jdbc, tx, mapper, "test"),
            TrustedIamSource(app.issuerEnvironmentRef, "test"), mapOf("dutch-development" to app))
        val ingestion = IamEventIngestionService(jdbc, adapter, "iam")
        val registration = WorkloadRegistration(workload, "dutch-development", "iam-relay",
            setOf(audience), setOf("ums:onboarding:write"))
        val resolver = WorkloadCallerResolver(issuer, audience, "ums:onboarding:write",
            WorkloadRegistrations(listOf(registration)), Clock.fixed(now, ZoneOffset.UTC))
        controller = IamIngressController(resolver, ingestion)
    }

    private fun jwt(appId: String = "dutch-development", scope: String = "ums:onboarding:write"): Jwt {
        val claims = mapOf<String, Any>(
            "iss" to issuer, "sub" to workload, "aud" to audience,
            "iat" to now.epochSecond, "exp" to now.plusSeconds(300).epochSecond,
            "jti" to "0123456789abcdef", "client_id" to "iam-relay",
            "token_profile" to "workload_access", "token_version" to 1,
            "subject_kind" to "workload", "scope" to scope, "app_id" to appId, "security_version" to 1,
        )
        return Jwt.withTokenValue("token").header("typ", "at+jwt").header("alg", "ES256").header("kid", "key-1")
            .claims { it.putAll(claims) }.issuedAt(now).expiresAt(now.plusSeconds(300)).build()
    }

    private fun event(id: String, subject: String = "aps_0123456789abcdefghijkL",
                      activation: String = "act_0123456789abcdef") = """
        {"eventId":"$id","eventType":"iam.app-subject.onboarding-ready.v1","schemaVersion":1,
        "occurredAt":"2026-11-08T07:00:00Z","producer":"iam","environment":"test",
        "aggregate":{"type":"APP_ONBOARDING","id":"$activation","version":1},
        "correlationId":"corr-1","causationId":null,"destination":{"appRegistrationId":"dutch-development"},
        "payload":{"appRegistrationId":"dutch-development","appSubject":"$subject",
        "activationId":"$activation","activationState":"ACTIVE","activationVersion":7,
        "onboardingReason":"FIRST_ACTIVATION","onboardingVersion":1,"requiredProfileState":"PROFILE_REQUIRED"}}
    """.trimIndent()

    private fun deliver(body: String, key: String, token: Jwt = jwt()): Int {
        val request = MockHttpServletRequest("POST", "/internal/v1/iam/events").apply {
            setContent(body.toByteArray())
            addHeader("X-Correlation-ID", "cor_0123456789abcdef")
        }
        return controller.ingest(token, request, MockHttpServletResponse(), key).statusCode.value()
    }

    @Test fun `two deliveries apply once each and a lost-response replay has no second effect`() {
        assertThat(deliver(event("evt_0123456789abcdef01"), "evt_0123456789abcdef01")).isEqualTo(202)
        assertThat(deliver(event("evt_0123456789abcdef02", "aps_fedcba9876543210hijkLM", "act_fedcba9876543210"),
            "evt_0123456789abcdef02")).isEqualTo(202)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(2)

        val outbox = jdbc.queryForObject(
            "select count(*) from ums_event_outbox where event_type = 'ums.profile.state-changed.v1'",
            Long::class.java)
        // The same event id is delivered again after a lost response.
        assertThat(deliver(event("evt_0123456789abcdef02", "aps_fedcba9876543210hijkLM", "act_fedcba9876543210"),
            "evt_0123456789abcdef02")).isEqualTo(202)
        assertThat(jdbc.queryForObject(
            "select count(*) from ums_event_outbox where event_type = 'ums.profile.state-changed.v1'",
            Long::class.java)).isEqualTo(outbox)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(2)
    }

    @Test fun `wrong authority is 404 and changes nothing`() {
        assertThat(deliver(event("evt_0123456789abcdef01"), "evt_0123456789abcdef01", jwt(appId = "other-app")))
            .isEqualTo(404)
        assertThat(deliver(event("evt_0123456789abcdef01"), "evt_0123456789abcdef01", jwt(scope = "other:scope")))
            .isEqualTo(404)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isZero()
    }
}
