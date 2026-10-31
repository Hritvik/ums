package com.vik.ums.privacy

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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Testcontainers
class ProfileFinalizationTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = MutableClock(Instant.parse("2026-10-24T00:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var deletion: ProfileDeletionService
    private lateinit var finalizer: ProfileFinalizationService
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var editing: ProfileEditingService
    private lateinit var registry: ProfilePolicyRegistry
    private fun human(purpose: PrivacyPurpose) = TrustedPrivacyAuthority(scope, UUID.randomUUID(), purpose,
        clock.instant(), true, clock.instant().plusSeconds(60))

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_fences, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests,
            ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox,
            ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        registry = ProfilePolicyRegistry(jdbc, tx, mapper)
        registry.register(AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "finalization"))
        editing = ProfileEditingService(jdbc, tx, mapper, "test", ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")),
            HmacPatchFingerprint(ByteArray(32) { 6 }), clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, tx, mapper, "test", clock)
    }

    @Test fun `due app-only request erases local fields once and keeps scoped completed status`() {
        val editActor = TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT))
        val editCommand = ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Private Name",
            ProfileField.DISPLAY_LOCALE to "en", ProfileField.DISPLAY_TIMEZONE to "UTC"), "finalization")
        editing.patch(editActor, editCommand)
        val accepted = deletion.request(human(PrivacyPurpose.REQUEST_DELETION), DeletionRequestCommand(UUID.randomUUID(), 2, "finalization"))
        clock.advance(Duration.ofDays(30))
        val authority = TrustedMaintenanceAuthority(app, UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION)
        val completed = finalizer.finalize(authority, scope, accepted.operationReference, "finalization")
        assertThat(completed.status).isEqualTo("LOCAL_COMPLETED")
        assertThat(completed.operationRevision).isEqualTo("2")
        assertThat(completed.bindingRevision).isEqualTo("4")
        assertThat(completed.cancellationEligible).isFalse()
        assertThat(finalizer.finalize(authority, scope, accepted.operationReference, "retry")).isEqualTo(completed)
        assertThat(deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)).isEqualTo(completed)
        val erased = mapper.readTree(jdbc.queryForObject("select payload::text from ums_event_outbox where stream_revision=4", String::class.java))
        assertThat(erased["reason"].asText()).isEqualTo("ERASED")
        assertThat(erased["state"]["lifecycle"].asText()).isEqualTo("ERASED")
        assertThat(erased["state"].has("profileReference")).isFalse()
        write("deletion-erased-event", mapper.writeValueAsString(erased))
        write("deletion-completed-status", mapper.writeValueAsString(completed))
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox where stream_revision=4", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_input_checkpoints", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_inbox", Long::class.java)).isEqualTo(1)
        assertThatThrownBy { editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings where display_name is not null or avatar_reference is not null or display_locale is not null or display_timezone is not null", Long::class.java)).isZero()
        assertThatThrownBy { editing.patch(editActor, editCommand) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(provisioning.provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 2, "finalization")).outcome)
            .isEqualTo(ProvisioningOutcome.RESTRICTED)
    }

    @Test fun `due boundary is exact and unsupported avatar blocks without cleanup claims`() {
        val accepted = request()
        clock.advance(Duration.ofDays(30).minusNanos(1))
        assertThatThrownBy { finish(accepted.operationReference) }.isInstanceOf(IllegalStateException::class.java)
        clock.advance(Duration.ofNanos(1))
        jdbc.update("update ums_profile_bindings set avatar_reference='seeded-provider-asset'")
        assertThatThrownBy { finish(accepted.operationReference) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("select status from ums_profile_deletion_requests", String::class.java)).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_fences", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings", String::class.java)).isEqualTo("seeded-provider-asset")
    }

    @Test fun `wrong scope cancelled requests and replacement generations cannot finalize`() {
        val accepted = request()
        val authority = TrustedMaintenanceAuthority(app.copy(appReference=UUID.randomUUID()), UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION)
        assertThatThrownBy { finalizer.finalize(authority, scope, accepted.operationReference, "finalization") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { finalizer.finalize(maintenance(), scope.copy(subjectReference=UUID.randomUUID()), accepted.operationReference, "finalization") }
            .isInstanceOf(IllegalStateException::class.java)
        deletion.cancel(human(PrivacyPurpose.CANCEL_DELETION), DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "finalization"))
        clock.advance(Duration.ofDays(30))
        assertThatThrownBy { finish(accepted.operationReference) }.isInstanceOf(IllegalStateException::class.java)
        val second = deletion.request(human(PrivacyPurpose.REQUEST_DELETION), DeletionRequestCommand(UUID.randomUUID(), 3, "finalization"))
        jdbc.update("update ums_profile_bindings set profile_reference=?, generation=2", UUID.randomUUID())
        clock.advance(Duration.ofDays(30))
        assertThatThrownBy { finish(second.operationReference) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `late outbox failure rolls back purge fence status and audit atomically`() {
        jdbc.update("update ums_profile_bindings set display_name='Private', display_locale='en', display_timezone='UTC'")
        val accepted = request()
        clock.advance(Duration.ofDays(30))
        jdbc.execute("alter table ums_event_outbox add constraint finalization_outbox_failure check (stream_revision <> 3)")
        try {
            assertThatThrownBy { finish(accepted.operationReference) }.isInstanceOf(RuntimeException::class.java)
            assertThat(jdbc.queryForObject("select display_name from ums_profile_bindings", String::class.java)).isEqualTo("Private")
            assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo("DELETION_PENDING")
            assertThat(jdbc.queryForObject("select status from ums_profile_deletion_requests", String::class.java)).isEqualTo("PENDING")
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_fences", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_privacy_audit", Long::class.java)).isEqualTo(1)
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint finalization_outbox_failure") }
        assertThat(finish(accepted.operationReference).status).isEqualTo("LOCAL_COMPLETED")
    }

    @Test fun `concurrent finalization retries erase once while cancellation at deadline loses`() {
        val accepted = request()
        clock.advance(Duration.ofDays(30))
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll(listOf(
                Callable { runCatching<Any> { finish(accepted.operationReference) } },
                Callable { runCatching<Any> { finish(accepted.operationReference) } },
                Callable { runCatching<Any> { deletion.cancel(human(PrivacyPurpose.CANCEL_DELETION),
                    DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "finalization")) } },
            )).map { it.get() }
            assertThat(results.count { it.isSuccess }).isEqualTo(2)
            assertThat(results.count { it.exceptionOrNull() is IllegalStateException }).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_fences", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox where stream_revision=3", Long::class.java)).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `local completion fence hides old cancelled status and rejects a later replacement`() {
        val old = request()
        deletion.cancel(human(PrivacyPurpose.CANCEL_DELETION), DeletionCancellationCommand(old.operationReference, UUID.randomUUID(), 2, "finalization"))
        val current = deletion.request(human(PrivacyPurpose.REQUEST_DELETION), DeletionRequestCommand(UUID.randomUUID(), 3, "finalization"))
        clock.advance(Duration.ofDays(30))
        finish(current.operationReference)
        assertThatThrownBy { deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), old.operationReference) }
            .isInstanceOf(IllegalStateException::class.java)
        jdbc.update("""update ums_profile_bindings set lifecycle='ACTIVE', readiness='PROFILE_READY',
            profile_reference=?, generation=2, profile_revision=1, binding_revision=binding_revision+1""", UUID.randomUUID())
        assertThatThrownBy { finish(current.operationReference) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), current.operationReference) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `accepted local obligation survives adoption switch and leaves other application untouched`() {
        val other = AppScope(app.issuerEnvironmentRef, UUID.randomUUID())
        registry.register(AppProfilePolicy(other, true, 1, emptySet(), emptySet(), "l1", "t1"))
        val otherScope = ProfileScope(other.issuerEnvironmentRef, other.appReference, scope.subjectReference)
        provisioning.provision(TrustedOnboardingFact(otherScope, "iam", UUID.randomUUID(), "entry", 1, "finalization"))
        val original = provisioning.status(other, otherScope)
        val accepted = request()
        jdbc.update("update ums_profile_policies set policy=jsonb_set(policy, '{adopted}', 'false'::jsonb) where app_reference=?", app.appReference)
        val disabledPending = deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)
        assertThat(disabledPending.status).isEqualTo("PENDING")
        assertThat(disabledPending.cancellationEligible).isFalse()
        clock.advance(Duration.ofDays(30))
        assertThat(finish(accepted.operationReference).status).isEqualTo("LOCAL_COMPLETED")
        assertThat(deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference).status).isEqualTo("LOCAL_COMPLETED")
        assertThat(provisioning.status(other, otherScope)).isEqualTo(original)
    }

    @Test fun `local tombstone clears closure and active causes without inventing global finalization`() {
        val accepted = request()
        jdbc.update("update ums_profile_bindings set closure_present=true")
        jdbc.update("""insert into ums_profile_deletion_causes values (?, ?, ?, 'IAM_GLOBAL', ?, 41, ?, ?)""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, UUID.randomUUID(),
            java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(40))))
        clock.advance(Duration.ofDays(30))
        assertThat(finish(accepted.operationReference).status).isEqualTo("LOCAL_COMPLETED")
        assertThat(jdbc.queryForObject("select closure_present from ums_profile_bindings", Boolean::class.java)).isFalse()
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Long::class.java)).isZero()
        jdbc.update("update ums_profile_erasure_fences set retired_generation=99")
        assertThatThrownBy { finish(accepted.operationReference) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { deletion.status(human(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun request() = deletion.request(human(PrivacyPurpose.REQUEST_DELETION), DeletionRequestCommand(UUID.randomUUID(), 1, "finalization"))
    private fun maintenance() = TrustedMaintenanceAuthority(app, UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION)
    private fun finish(reference: UUID) = finalizer.finalize(maintenance(), scope, reference, "finalization")
    private fun write(name: String, json: String) {
        val path = java.nio.file.Path.of("build/generated-contract-fixtures/$name.json")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, json)
    }

    private class MutableClock(@Volatile private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
    }
}
