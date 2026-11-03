package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.profiles.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Testcontainers
class IamOnboardingAdapterTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private lateinit var jdbc: JdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var adapter: IamOnboardingAdapter

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies")
        tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t1"))
        adapter = IamOnboardingAdapter(jdbc, tx, ProfileProvisioningService(jdbc, tx, mapper, "test"),
            TrustedIamSource(app.issuerEnvironmentRef, "test"), mapOf("dutch-development" to app))
    }

    @Test fun `trusted provider event provisions once through durable random subject mapping`() {
        val original = event()
        val result = adapter.consume(original, "dutch-development")
        val fixture = java.nio.file.Path.of("build/generated-contract-fixtures/iam-onboarding-input.json")
        java.nio.file.Files.createDirectories(fixture.parent)
        java.nio.file.Files.writeString(fixture, original)
        assertThat(result.outcome).isEqualTo(ProvisioningOutcome.PROVISIONED)
        assertThat(result.state?.scope?.application()).isEqualTo(app)
        assertThat(adapter.consume(original, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.DUPLICATE)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select app_subject from ums_iam_subject_mappings", String::class.java))
            .isEqualTo("aps_0123456789abcdefghijkL")
        assertThat(jdbc.queryForObject("select activation_version from ums_iam_onboarding_receipts", Long::class.java)).isEqualTo(7)
        assertThat(jdbc.queryForObject("select onboarding_version from ums_iam_onboarding_receipts", Long::class.java)).isEqualTo(1)
        assertThatThrownBy { adapter.consume(original.replace("\"activationVersion\":7", "\"activationVersion\":8"), "dutch-development") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `gaps and changed activation cannot provision or reset ordering`() {
        val gap = event(2)
        assertThat(adapter.consume(gap, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.RECONCILIATION_REQUIRED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isZero()
        adapter.consume(event(id = "evt_first00000000000"), "dutch-development")
        assertThat(adapter.consume(gap, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.STALE)
        assertThat(adapter.consume(event(1, "evt_second0000000000", "act_other00000000000"), "dutch-development").outcome)
            .isEqualTo(ProvisioningOutcome.RECONCILIATION_REQUIRED)
        assertThat(jdbc.queryForObject("select source_revision from ums_input_checkpoints", Long::class.java)).isEqualTo(2)
        jdbc.update("update ums_profile_bindings set lifecycle='CLOSED', readiness='PROFILE_REQUIRED'")
        assertThat(adapter.consume(event(3, "evt_third00000000000"), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.RESTRICTED)
    }

    @Test fun `strict contract violations and unsafe numeric versions produce no effects`() {
        val original = event()
        val invalid = listOf(
            original.replace("\"producer\":\"iam\"", "\"producer\":\"other\""),
            original.replace("\"environment\":\"test\"", "\"environment\":\"production\""),
            original.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            original.replace("\"activationVersion\":7", "\"activationVersion\":9223372036854775808"),
            original.replace("\"onboardingVersion\":1", "\"onboardingVersion\":1.5"),
            original.replace("\"onboardingVersion\":1", "\"onboardingVersion\":0"),
            original.replace("\"onboardingVersion\":1", "\"onboardingVersion\":2"),
            original.replace("\"producer\":\"iam\"", "\"producer\":\"iam\",\"email\":\"private\""),
            original.replace("\"activationState\":\"ACTIVE\"", "\"activationState\":\"CLOSED\""),
            original.replace("\"requiredProfileState\":\"PROFILE_REQUIRED\"", "\"requiredProfileState\":\"PROFILE_READY\""),
            original.replace("\"correlationId\":\"fixture-1\"", "\"correlationId\":\"fixture-1\",\"correlationId\":\"duplicate\""),
            original + " {}",
            original.replace("2026-10-23T10:15:30Z", "not-time"),
            original.replace("2026-10-23T10:15:30Z", "0000-10-23T10:15:30Z"),
            original.replace("2026-10-23T10:15:30Z", "2026-10-23T10:15Z"),
            original.replace("2026-10-23T10:15:30Z", "2026-10-23T10:15:30+01:00:01"),
            original.replace("dutch-development", "unknown-app"),
        )
        invalid.forEach { value -> assertThatThrownBy { adapter.consume(value, "dutch-development") }.isInstanceOf(IllegalArgumentException::class.java) }
        assertThat(jdbc.queryForObject("select count(*) from ums_iam_subject_mappings", Long::class.java)).isZero()
    }

    @Test fun `source metadata and long versions remain distinct and lossless`() {
        val value = event(9007199254740993).replace("\"activationVersion\":7", "\"activationVersion\":9223372036854775807")
        assertThat(adapter.consume(value, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.RECONCILIATION_REQUIRED)
        assertThat(jdbc.queryForObject("select activation_version from ums_iam_onboarding_receipts", Long::class.java))
            .isEqualTo(Long.MAX_VALUE)
        assertThat(jdbc.queryForObject("select onboarding_version from ums_iam_onboarding_receipts", Long::class.java))
            .isEqualTo(9007199254740993)
    }

    @Test fun `restart and concurrent retries reuse durable identities`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val outcomes = pool.invokeAll((1..8).map { Callable { adapter.consume(event(), "dutch-development").outcome } }).map { it.get() }
            assertThat(outcomes.count { it == ProvisioningOutcome.PROVISIONED }).isEqualTo(1)
            assertThat(outcomes.count { it == ProvisioningOutcome.DUPLICATE }).isEqualTo(7)
        } finally { pool.shutdownNow() }
        val restarted = newAdapter(TrustedIamSource(app.issuerEnvironmentRef, "test"), mapOf("dutch-development" to app))
        assertThat(restarted.consume(event(), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.DUPLICATE)
        assertThat(jdbc.queryForObject("select count(*) from ums_iam_subject_mappings", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isEqualTo(1)
    }

    @Test fun `source and app mappings isolate original subjects and prohibit event rebind`() {
        adapter.consume(event(), "dutch-development")
        val other = app.copy(appReference = UUID.randomUUID())
        register(other)
        val both = newAdapter(TrustedIamSource(app.issuerEnvironmentRef, "test"),
            mapOf("dutch-development" to app, "midas-development" to other))
        val otherEvent = event().replace("dutch-development", "midas-development")
        assertThatThrownBy { both.consume(otherEvent, "midas-development") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(both.consume(otherEvent.replace("evt_0123456789abcdef", "evt_other00000000000"), "midas-development").outcome)
            .isEqualTo(ProvisioningOutcome.PROVISIONED)
        val separate = AppScope(UUID.randomUUID(), UUID.randomUUID())
        register(separate)
        assertThat(newAdapter(TrustedIamSource(separate.issuerEnvironmentRef, "test"), mapOf("dutch-development" to separate))
            .consume(event(), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.PROVISIONED)
        val references = jdbc.query("select subject_reference from ums_iam_subject_mappings", { rs, _ -> rs.getObject(1, UUID::class.java) })
        assertThat(references).hasSize(3).doesNotHaveDuplicates()
        val alias = newAdapter(TrustedIamSource(app.issuerEnvironmentRef, "production"), mapOf("dutch-development" to app))
        assertThatThrownBy { alias.consume(event().replace("\"environment\":\"test\"", "\"environment\":\"production\""), "dutch-development") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { adapter.consume(event(id = "evt_changed000000000").replace("\"activationVersion\":7", "\"activationVersion\":8"), "dutch-development") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `outbox failure rolls back all adapter effects and disabled adoption creates none`() {
        jdbc.execute("alter table ums_event_outbox add constraint test_iam_failure check (stream_revision <> 1)")
        try {
            assertThatThrownBy { adapter.consume(event(), "dutch-development") }.isInstanceOf(RuntimeException::class.java)
            listOf("ums_iam_source_bindings", "ums_iam_app_mappings", "ums_iam_subject_mappings", "ums_iam_onboarding_receipts",
                "ums_event_inbox", "ums_profile_bindings", "ums_event_outbox").forEach { table ->
                assertThat(jdbc.queryForObject("select count(*) from $table", Long::class.java)).describedAs(table).isZero()
            }
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint test_iam_failure") }
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy, '{adopted}', 'false'::jsonb)")
        assertThat(adapter.consume(event(), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.NOT_ADOPTED)
        assertThat(jdbc.queryForObject("select count(*) from ums_iam_subject_mappings", Long::class.java)).isZero()
    }

    @Test fun `activation aggregate cannot be rebound to another app subject`() {
        adapter.consume(event(), "dutch-development")
        val rebound = event(id = "evt_rebound000000000")
            .replace("aps_0123456789abcdefghijkL", "aps_other00000000000000000")
        assertThatThrownBy { adapter.consume(rebound, "dutch-development") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(1)
    }

    @Test fun `snapshot reconciliation reason never authorizes provisioning or durable mutation`() {
        val snapshotDerived = event().replace("FIRST_ACTIVATION", "RECONCILIATION")
        assertThat(adapter.consume(snapshotDerived, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.RECONCILIATION_REQUIRED)
        listOf("ums_iam_source_bindings", "ums_iam_app_mappings", "ums_iam_subject_mappings", "ums_iam_onboarding_receipts",
            "ums_event_inbox", "ums_profile_bindings", "ums_event_outbox").forEach { table ->
            assertThat(jdbc.queryForObject("select count(*) from $table", Long::class.java)).describedAs(table).isZero()
        }
    }

    @Test fun `RFC3339 fractional timestamps within provider bound remain accepted losslessly`() {
        val fractional = event().replace("2026-10-23T10:15:30Z", "2026-10-23T10:15:30.12345678901234Z")
        assertThat(adapter.consume(fractional, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.PROVISIONED)
        assertThat(adapter.consume(fractional, "dutch-development").outcome).isEqualTo(ProvisioningOutcome.DUPLICATE)
    }

    private fun newAdapter(source: TrustedIamSource, registrations: Map<String, AppScope>) =
        IamOnboardingAdapter(jdbc, tx, ProfileProvisioningService(jdbc, tx, mapper, "test"), source, registrations)
    private fun register(target: AppScope) = ProfilePolicyRegistry(jdbc, tx, mapper).register(
        AppProfilePolicy(target, true, 1, emptySet(), emptySet(), "l1", "t1"))

    private fun event(revision: Long = 1, id: String = "evt_0123456789abcdef", activation: String = "act_0123456789abcdef"): String = """
        {"eventId":"$id","eventType":"iam.app-subject.onboarding-ready.v1","schemaVersion":1,
        "occurredAt":"2026-10-23T10:15:30Z","producer":"iam","environment":"test",
        "aggregate":{"type":"APP_ONBOARDING","id":"$activation","version":$revision},
        "correlationId":"fixture-1","causationId":null,"destination":{"appRegistrationId":"dutch-development"},
        "payload":{"appRegistrationId":"dutch-development","appSubject":"aps_0123456789abcdefghijkL",
        "activationId":"$activation","activationState":"ACTIVE","activationVersion":7,
        "onboardingReason":"FIRST_ACTIVATION","onboardingVersion":$revision,"requiredProfileState":"PROFILE_REQUIRED"}}
    """.trimIndent()
}
