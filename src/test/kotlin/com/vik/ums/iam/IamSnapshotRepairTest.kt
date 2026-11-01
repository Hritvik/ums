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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
class IamSnapshotRepairTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine")
        const val SUBJECT = "aps_0123456789abcdefghijkL"
        const val ACTIVATION = "act_0123456789abcdef"
    }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val source = TrustedIamSource(app.issuerEnvironmentRef, "test")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repair: IamSnapshotRepairService

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_iam_ingestion_quarantine, ums_profile_notice_intents,
            ums_profile_erasure_obligations, ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox,
            ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t1"))
        val adapter = IamOnboardingAdapter(jdbc, tx, ProfileProvisioningService(jdbc, tx, mapper, "test"),
            source, mapOf("dutch-development" to app))
        adapter.consume(event(), "dutch-development")
        repair = IamSnapshotRepairService(jdbc, tx, source, registeredProducer = "iam")
    }

    private fun request(body: String, subject: String = SUBJECT, producer: String = "iam",
                        application: String = "dutch-development") =
        SnapshotRepairRequest(application, producer, subject, "repair", body.toByteArray())

    private fun snapshot(version: Long, subject: String = SUBJECT, activation: String = ACTIVATION,
                         application: String = "dutch-development", hasMore: Boolean = false) = """
        {"data":{"appRegistrationId":"$application","items":[{"appSubject":"$subject",
        "activationId":"$activation","activationState":"ACTIVE","activationVersion":1,
        "onboardingReason":"RECONCILIATION","onboardingVersion":$version,
        "requiredProfileState":"PROFILE_REQUIRED"}],"hasMore":$hasMore}}
    """.trimIndent()

    private fun checkpoint(): Long = jdbc.queryForObject("select source_revision from ums_input_checkpoints",
        Long::class.java)!!

    @Test fun `a newer snapshot version advances the checkpoint without new profile state`() {
        val result = repair.repair(request(snapshot(2)))

        assertThat(result.outcome).isEqualTo(SnapshotRepairOutcome.CHECKPOINT_CONVERGED)
        assertThat(result.checkpoint).isEqualTo(2)
        assertThat(checkpoint()).isEqualTo(2)
        assertThat(jdbc.queryForObject("select binding_revision from ums_profile_bindings", Long::class.java))
            .isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isEqualTo(1)
    }

    @Test fun `an equal or older snapshot version changes nothing`() {
        assertThat(repair.repair(request(snapshot(1))).outcome).isEqualTo(SnapshotRepairOutcome.NO_CHANGE)
        assertThat(checkpoint()).isEqualTo(1)
    }

    @Test fun `a snapshot never restores an erased or reopens a restricted profile`() {
        jdbc.update("update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED'")
        assertThat(repair.repair(request(snapshot(2))).outcome).isEqualTo(SnapshotRepairOutcome.REFUSED_RESTRICTED)

        jdbc.update("update ums_profile_bindings set lifecycle = 'DELETION_PENDING'")
        assertThat(repair.repair(request(snapshot(2))).outcome).isEqualTo(SnapshotRepairOutcome.REFUSED_RESTRICTED)

        jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED', readiness = 'PROFILE_REQUIRED',
            profile_reference = null, generation = null, profile_revision = null""")
        assertThat(repair.repair(request(snapshot(2))).outcome).isEqualTo(SnapshotRepairOutcome.REFUSED_ERASED)
        assertThat(checkpoint()).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isEqualTo(1)
    }

    @Test fun `a different activation requires reconciliation instead of being overwritten`() {
        assertThat(repair.repair(request(snapshot(2, activation = "act_0123456789abcdee"))).outcome)
            .isEqualTo(SnapshotRepairOutcome.RECONCILIATION_REQUIRED)
        assertThat(checkpoint()).isEqualTo(1)
    }

    @Test fun `missing local binding and missing snapshot evidence are distinct`() {
        val unknown = "aps_9999999999999999999999"
        assertThat(repair.repair(request(snapshot(2, subject = unknown), subject = unknown)).outcome)
            .isEqualTo(SnapshotRepairOutcome.NO_LOCAL_BINDING)
        assertThat(repair.repair(request(snapshot(2, subject = unknown))).outcome)
            .isEqualTo(SnapshotRepairOutcome.NO_EVIDENCE)
    }

    @Test fun `malformed unattributable or partial payloads are rejected`() {
        assertThat(repair.repair(request("{not json")).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(snapshot(2, application = "midas-development"))).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(snapshot(2).replace("\"hasMore\":false", "\"extra\":1,\"hasMore\":false")))
            .outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(snapshot(2), producer = "other")).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(SnapshotRepairRequest("dutch-development", "iam", SUBJECT, "repair",
            ByteArray(300 * 1024) { 'x'.code.toByte() })).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(checkpoint()).isEqualTo(1)
    }

    @Test fun `ambiguous or invalid snapshot items are rejected`() {
        fun page(items: String) =
            """{"data":{"appRegistrationId":"dutch-development","items":[$items],"hasMore":false}}"""
        val item = """{"appSubject":"$SUBJECT","activationId":"$ACTIVATION","activationState":"ACTIVE",
            "activationVersion":1,"onboardingReason":"RECONCILIATION","onboardingVersion":2,
            "requiredProfileState":"PROFILE_REQUIRED"}"""

        assertThat(repair.repair(request(page("$item,$item"))).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(List(201) { item }.joinToString(",")))).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(item.replace("\"activationState\":\"ACTIVE\"",
            "\"activationState\":\"SUSPENDED\"")))).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(item.replace("PROFILE_REQUIRED", "PROFILE_READY")))).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(item.replace("\"onboardingVersion\":2",
            "\"onboardingVersion\":2.5")))).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(item.replace("RECONCILIATION", "OTHER")))).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(page(""))).outcome).isEqualTo(SnapshotRepairOutcome.NO_EVIDENCE)
    }

    @Test fun `unregistered applications and undecodable bodies are rejected`() {
        assertThat(repair.repair(request(snapshot(2), application = "midas-development")).outcome)
            .isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(SnapshotRepairRequest("dutch-development", "iam", SUBJECT, "repair",
            byteArrayOf(0xC3.toByte(), 0x28))).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
    }

    @Test fun `an optional cursor does not affect a complete page`() {
        val body = snapshot(2).replace("\"hasMore\":false", "\"nextCursor\":\"cursor-1\",\"hasMore\":false")
        assertThat(repair.repair(request(body)).outcome).isEqualTo(SnapshotRepairOutcome.CHECKPOINT_CONVERGED)
    }

    @Test fun `structurally invalid pages are rejected`() {
        val item = """{"appSubject":"$SUBJECT","activationId":"$ACTIVATION","activationState":"ACTIVE",
            "activationVersion":1,"onboardingReason":"RECONCILIATION","onboardingVersion":2,
            "requiredProfileState":"PROFILE_REQUIRED"}"""
        listOf(
            """{"data":{"appRegistrationId":"dutch-development","items":[$item]}}""",
            """{"data":{"appRegistrationId":"dutch-development","items":$item,"hasMore":false}}""",
            """{"data":{"appRegistrationId":"dutch-development","items":[$item],"hasMore":"no"}}""",
            """{"items":[$item],"hasMore":false}""",
            """[]""",
            """{"data":{"appRegistrationId":"dutch-development","items":[${item.replace("\"onboardingVersion\":2", "\"onboardingVersion\":0")}],"hasMore":false}}""",
            """{"data":{"appRegistrationId":"dutch-development","items":[${item.replace("\"activationId\":\"$ACTIVATION\",", "")}],"hasMore":false}}""",
            """{"data":{"appRegistrationId":"dutch-development","items":[${item.replace("\"appSubject\":\"$SUBJECT\",", "")}],"hasMore":false}}""",
        ).forEach { body ->
            assertThat(repair.repair(request(body)).outcome).describedAs(body).isEqualTo(SnapshotRepairOutcome.REJECTED)
        }
        assertThat(checkpoint()).isEqualTo(1)
    }

    @Test fun `a truncated page repairs a present subject but cannot conclude absence`() {
        assertThat(repair.repair(request(snapshot(2, hasMore = true))).outcome)
            .isEqualTo(SnapshotRepairOutcome.CHECKPOINT_CONVERGED)
        val other = "aps_9999999999999999999999"
        // The page is truncated and does not contain the requested subject: absence is not evidence.
        assertThat(repair.repair(request(snapshot(2, hasMore = true), subject = other)).outcome)
            .isEqualTo(SnapshotRepairOutcome.PAGE_INCOMPLETE)
    }

    @Test fun `a mapped subject with no local binding is never created`() {
        jdbc.update("delete from ums_profile_bindings")

        assertThat(repair.repair(request(snapshot(2))).outcome).isEqualTo(SnapshotRepairOutcome.NO_LOCAL_BINDING)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Int::class.java)).isZero()
    }

    @Test fun `an unregistered application is rejected even with a matching page`() {
        assertThat(repair.repair(request(snapshot(2, application = "midas-development"),
            application = "midas-development")).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
        assertThat(repair.repair(request(snapshot(2).replace("\"hasMore\":false",
            "\"nextCursor\":5,\"hasMore\":false"))).outcome).isEqualTo(SnapshotRepairOutcome.REJECTED)
    }

    private fun event(revision: Long = 1): String = """
        {"eventId":"evt_0123456789abcdef","eventType":"iam.app-subject.onboarding-ready.v1","schemaVersion":1,
        "occurredAt":"2026-10-23T10:15:30Z","producer":"iam","environment":"test",
        "aggregate":{"type":"APP_ONBOARDING","id":"$ACTIVATION","version":$revision},
        "correlationId":"fixture-1","causationId":null,"destination":{"appRegistrationId":"dutch-development"},
        "payload":{"appRegistrationId":"dutch-development","appSubject":"$SUBJECT",
        "activationId":"$ACTIVATION","activationState":"ACTIVE","activationVersion":7,
        "onboardingReason":"FIRST_ACTIVATION","onboardingVersion":$revision,"requiredProfileState":"PROFILE_REQUIRED"}}
    """.trimIndent()
}
