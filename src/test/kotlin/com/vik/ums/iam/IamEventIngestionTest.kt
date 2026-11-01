package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileProvisioningService
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
import java.util.UUID

@Testcontainers
class IamEventIngestionTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private lateinit var jdbc: JdbcTemplate
    private lateinit var ingestion: IamEventIngestionService

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
        ingestion = IamEventIngestionService(jdbc, adapter, registeredProducer = "iam")
    }

    private fun request(body: ByteArray, producer: String = "iam", application: String = "dutch-development") =
        TrustedProducerRequest(application, producer, "ingest", body)

    private fun request(body: String, producer: String = "iam", application: String = "dutch-development") =
        request(body.toByteArray(), producer, application)

    private fun event(revision: Long = 1, id: String = "evt_0123456789abcdef"): String = """
        {"eventId":"$id","eventType":"iam.app-subject.onboarding-ready.v1","schemaVersion":1,
        "occurredAt":"2026-10-23T10:15:30Z","producer":"iam","environment":"test",
        "aggregate":{"type":"APP_ONBOARDING","id":"act_0123456789abcdef","version":$revision},
        "correlationId":"fixture-1","causationId":null,"destination":{"appRegistrationId":"dutch-development"},
        "payload":{"appRegistrationId":"dutch-development","appSubject":"aps_0123456789abcdefghijkL",
        "activationId":"act_0123456789abcdef","activationState":"ACTIVE","activationVersion":7,
        "onboardingReason":"FIRST_ACTIVATION","onboardingVersion":$revision,"requiredProfileState":"PROFILE_REQUIRED"}}
    """.trimIndent()

    @Test fun `a valid registered event is applied once and duplicates are harmless`() {
        assertThat(ingestion.ingest(request(event())).outcome).isEqualTo(IngestionOutcome.APPLIED)
        assertThat(ingestion.ingest(request(event())).outcome).isEqualTo(IngestionOutcome.DUPLICATE)

        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isEqualTo(1)
        assertThat(ingestion.openQuarantineCount()).isZero()
    }

    @Test fun `a gap returns reconciliation instead of provisioning`() {
        assertThat(ingestion.ingest(request(event(revision = 2))).outcome)
            .isEqualTo(IngestionOutcome.RECONCILIATION_REQUIRED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isZero()
        assertThat(ingestion.openQuarantineCount()).isZero()
    }

    @Test fun `malformed unknown or foreign payloads are quarantined without mutation`() {
        assertThat(ingestion.ingest(request("{not json")).outcome).isEqualTo(IngestionOutcome.QUARANTINED)
        assertThat(ingestion.ingest(request(event().replace("\"activationVersion\":7", "\"unexpected\":1")))
            .outcome).isEqualTo(IngestionOutcome.QUARANTINED)
        assertThat(ingestion.ingest(request(event(), application = "midas-development")).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)

        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isZero()
        assertThat(jdbc.queryForList("select failure_class from ums_iam_ingestion_quarantine", String::class.java))
            .containsOnly("SCHEMA_INVALID", "APPLICATION_BINDING_MISMATCH")
        assertThat(ingestion.openQuarantineCount()).isEqualTo(3)
    }

    @Test fun `producer mismatch size and encoding are quarantined before parsing`() {
        assertThat(ingestion.ingest(request(event(), producer = "other")).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)
        assertThat(ingestion.ingest(request(ByteArray(40 * 1024) { 'x'.code.toByte() })).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)
        assertThat(ingestion.ingest(request(byteArrayOf(0xC3.toByte(), 0x28))).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)

        assertThat(jdbc.queryForList("select failure_class from ums_iam_ingestion_quarantine order by received_at",
            String::class.java))
            .containsExactly("PRODUCER_MISMATCH", "PAYLOAD_TOO_LARGE", "INVALID_ENCODING")
        // Only a digest is retained, never the payload.
        assertThat(jdbc.queryForObject("select payload_digest from ums_iam_ingestion_quarantine limit 1",
            String::class.java)).matches("[a-f0-9]{64}")
    }

    @Test fun `quarantine can be resolved and reports its oldest open age`() {
        val result = ingestion.ingest(request("{not json"))
        val reference = result.quarantineReference!!

        assertThat(ingestion.oldestOpenReceivedAt()).isNotNull()
        ingestion.resolve(reference)

        assertThat(ingestion.openQuarantineCount()).isZero()
        assertThat(ingestion.oldestOpenReceivedAt()).isNull()
        assertThatThrownBy { ingestion.resolve(reference) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `authorization rejects are classified apart from poison payloads`() {
        assertThat(ingestion.ingest(request(event(), application = "midas-development")).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)
        val midas = event().replace("dutch-development", "midas-development")
        assertThat(ingestion.ingest(request(midas, application = "midas-development")).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)
        assertThat(ingestion.ingest(request("""{"a":1,"a":1}""")).outcome)
            .isEqualTo(IngestionOutcome.QUARANTINED)

        assertThat(jdbc.queryForList("select failure_class from ums_iam_ingestion_quarantine order by received_at",
            String::class.java))
            .containsExactly("APPLICATION_BINDING_MISMATCH", "APPLICATION_NOT_REGISTERED", "SCHEMA_INVALID")
    }

    @Test fun `stale restricted and not-adopted outcomes are distinct`() {
        assertThat(ingestion.ingest(request(event())).outcome).isEqualTo(IngestionOutcome.APPLIED)
        assertThat(ingestion.ingest(request(event(id = "evt_other00000000000"))).outcome)
            .isEqualTo(IngestionOutcome.STALE)

        jdbc.update("update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED'")
        assertThat(ingestion.ingest(request(event(revision = 2, id = "evt_rev2000000000000"))).outcome)
            .isEqualTo(IngestionOutcome.RESTRICTED)

        jdbc.update("update ums_profile_bindings set lifecycle = 'ACTIVE'")
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy::jsonb, '{adopted}', 'false')")
        assertThat(ingestion.ingest(request(event(revision = 3, id = "evt_rev3000000000000"))).outcome)
            .isEqualTo(IngestionOutcome.NOT_ADOPTED)
    }

    @Test fun `an empty payload is rejected at the trust boundary`() {
        assertThatThrownBy {
            TrustedProducerRequest("dutch-development", "iam", "ingest", ByteArray(0))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
