package com.vik.ums.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.ProfileClosureService
import com.vik.ums.lifecycle.TrustedClosureFact
import com.vik.ums.privacy.DeletionCancellationCommand
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.privacy.TrustedPrivacyAuthority
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
class AssociationReportTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val clock = AdjustableClock(Instant.parse("2026-11-05T09:00:00Z"))
    private val issuer = UUID.randomUUID()
    private val app = UUID.randomUUID()
    private val subject = UUID.randomUUID()
    private val appSubject = "aps_0123456789abcdefghijkL"
    private val scope = ProfileScope(issuer, app, subject)
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var editing: ProfileEditingService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var finalizer: ProfileFinalizationService

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_notice_intents, ums_erasure_journal_feed,
            ums_profile_erasure_obligations, ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox,
            ums_event_outbox, ums_iam_ingestion_quarantine, ums_profile_bindings, ums_input_checkpoints,
            ums_profile_policies cascade""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(AppScope(issuer, app), true, 1, setOf(ProfileField.DISPLAY_NAME),
                ProfileField.entries.toSet(), "l1", "t1"))
        jdbc.update("insert into ums_iam_source_bindings (issuer_environment_ref, environment) values (?, 'test')", issuer)
        jdbc.update("""insert into ums_iam_app_mappings (issuer_environment_ref, app_registration_id, app_reference)
            values (?, 'dutch-development', ?)""", issuer, app)
        jdbc.update("""insert into ums_iam_subject_mappings
            (issuer_environment_ref, app_reference, app_subject, subject_reference, activation_id)
            values (?, ?, ?, ?, 'act_0123456789abcdef')""", issuer, app, appSubject, subject)
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "association"))
        editing = ProfileEditingService(jdbc, transactions, mapper, "test",
            ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")), HmacPatchFingerprint(ByteArray(32) { 3 }), clock)
        deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
    }

    private fun reports() = jdbc.queryForList("""select payload::text from ums_event_outbox
        where event_type = 'ums.profile.association-report.v1' order by stream_revision""",
        String::class.java).map { mapper.readTree(it) }

    private fun write(name: String, node: com.fasterxml.jackson.databind.JsonNode) {
        val directory = java.nio.file.Path.of("build", "generated-contract-fixtures")
        java.nio.file.Files.createDirectories(directory)
        java.nio.file.Files.writeString(directory.resolve("$name.json"), mapper.writeValueAsString(node))
    }

    private fun revision(): Long = jdbc.queryForObject(
        "select binding_revision from ums_profile_bindings where subject_reference = ?", Long::class.java, subject)!!

    private fun patch(name: String, locale: String = "en") {
        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), revision(),
                mapOf(ProfileField.DISPLAY_NAME to name, ProfileField.DISPLAY_LOCALE to locale), "association"))
    }

    @Test fun `reports follow readiness, lifecycle, cancellation, closure and erasure`() {
        val provisioned = reports().single()
        assertThat(provisioned["payload"]["profileState"].asText()).isEqualTo("PROFILE_REQUIRED")
        assertThat(provisioned["payload"]["lifecycleState"].asText()).isEqualTo("ACTIVE")
        assertThat(provisioned["aggregate"]["type"].asText()).isEqualTo("UMS_PROFILE_BINDING")
        assertThat(provisioned["aggregate"]["id"].asText()).isEqualTo(appSubject)
        assertThat(provisioned["destination"]["appRegistrationId"].asText()).isEqualTo("dutch-development")
        assertThat(provisioned["payload"]["generation"].asInt()).isEqualTo(1)
        assertThat(provisioned.toString()).doesNotContain("displayName", "Ada", "hid_", "Bearer")
        write("association-report", provisioned)

        patch("Ada")
        assertThat(reports()).hasSize(2)
        assertThat(reports().last()["payload"]["profileState"].asText()).isEqualTo("PROFILE_READY")

        // A display-only edit keeps readiness and lifecycle, so it does not report again.
        patch("Ada", locale = "en")
        assertThat(reports()).hasSize(2)

        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), revision(), "association"))
        assertThat(reports()).hasSize(3)
        assertThat(reports().last()["payload"]["lifecycleState"].asText()).isEqualTo("DELETION_PENDING")
        assertThat(reports().last()["payload"]["lifecycleReason"].asText()).isEqualTo("DELETION_REQUESTED")

        val accepted = jdbc.queryForObject(
            "select request_reference from ums_profile_deletion_requests where status = 'PENDING'",
            UUID::class.java)!!
        deletion.cancel(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.CANCEL_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionCancellationCommand(accepted, UUID.randomUUID(), revision(), "association"))
        assertThat(reports()).hasSize(4)
        assertThat(reports().last()["payload"]["lifecycleState"].asText()).isEqualTo("ACTIVE")
        assertThat(reports().last()["payload"]["lifecycleReason"].asText()).isEqualTo("DELETION_CANCELLED")

        ProfileClosureService(jdbc, transactions, mapper, "test", clock)
            .close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "association"))
        assertThat(reports()).hasSize(5)
        assertThat(reports().last()["payload"]["lifecycleState"].asText()).isEqualTo("CLOSED")

        val request = UUID.randomUUID()
        val acceptedAt = clock.instant().minus(Duration.ofDays(31))
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference, profile_reference,
             generation, operation_revision, status, accepted_at, deadline)
            select ?, issuer_environment_ref, app_reference, subject_reference, profile_reference,
                generation, 1, 'PENDING', ?, ?
            from ums_profile_bindings where subject_reference = ?""",
            request, java.sql.Timestamp.from(acceptedAt),
            java.sql.Timestamp.from(acceptedAt.plus(Duration.ofDays(30))), subject)
        jdbc.update("""update ums_profile_bindings set lifecycle = 'DELETION_PENDING',
            readiness = 'PROFILE_REQUIRED', closure_present = false, closure_at = null,
            closure_retain_until = null, closure_reference = null
            where subject_reference = ?""", subject)
        jdbc.update("""insert into ums_profile_deletion_causes
            (issuer_environment_ref, app_reference, subject_reference, cause_type, request_reference,
             source_revision, accepted_at, deadline)
            values (?, ?, ?, 'APP_ONLY', ?, 1, ?, ?)""",
            issuer, app, subject, request, java.sql.Timestamp.from(acceptedAt),
            java.sql.Timestamp.from(acceptedAt.plus(Duration.ofDays(30))))
        finalizer.finalize(TrustedMaintenanceAuthority(AppScope(issuer, app), UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, request, "association")

        val erased = reports().last()
        assertThat(erased["payload"]["lifecycleState"].asText()).isEqualTo("ERASED")
        assertThat(erased["payload"]["lifecycleReason"].asText()).isEqualTo("ERASED")
        assertThat(erased["payload"]["retiredGeneration"].asInt()).isEqualTo(1)
        assertThat(erased["payload"].has("generation")).isFalse()
        assertThat(erased["payload"].has("profileRevision")).isFalse()
        assertThat(erased["payload"]["contentObligation"].asBoolean()).isFalse()
        write("association-report-erased", erased)
    }

    @Test fun `a profile without an app subject mapping emits no report`() {
        val before = jdbc.queryForObject("""select count(*) from ums_event_outbox
            where event_type = 'ums.profile.association-report.v1'""", Int::class.java)!!
        val unmapped = ProfileScope(issuer, app, UUID.randomUUID())
        AssociationReports(jdbc, mapper, "test", clock).emit(
            BindingState(unmapped, "1", "1", Lifecycle.ACTIVE, Readiness.PROFILE_READY,
                UUID.randomUUID(), "1", "1"),
            "PROVISIONED", UUID.randomUUID(), "association")

        assertThat(jdbc.queryForObject("""select count(*) from ums_event_outbox
            where event_type = 'ums.profile.association-report.v1'""", Int::class.java)).isEqualTo(before)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
