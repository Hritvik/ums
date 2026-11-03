package com.vik.ums.lifecycle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.TrustedPrivacyAuthority
import com.vik.ums.profiles.*
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@Testcontainers
class ProfileReactivationTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-10-31T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var editing: ProfileEditingService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var closure: ProfileClosureService
    private lateinit var reactivation: ProfileReactivationService

    private fun close(closedAt: Instant = clock.instant()) =
        closure.close(TrustedClosureFact(scope, closedAt, UUID.randomUUID(), "lifecycle"))

    private fun reactivate() =
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))

    @BeforeEach fun setup() {
        val ds: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings,
            ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox,
            ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(
            AppProfilePolicy(app, true, 1, setOf(ProfileField.DISPLAY_NAME), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "lifecycle"))
        editing = ProfileEditingService(jdbc, tx, mapper, "test", ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")),
            HmacPatchFingerprint(ByteArray(32) { 8 }), clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        closure = ProfileClosureService(jdbc, tx, mapper, "test", clock)
        reactivation = ProfileReactivationService(jdbc, tx, mapper, "test", clock)
        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Ada"), "lifecycle"))
    }

    @Test fun `reactivation reopens a retained profile with readiness from the current policy`() {
        assertThat(provisioning.status(app, scope).state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        val closedAt = clock.instant()
        assertThat(close(closedAt).outcome).isEqualTo(ClosureOutcome.CLOSED)
        assertThat(provisioning.status(app, scope).state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)

        val result = reactivate()

        assertThat(result.outcome).isEqualTo(ReactivationOutcome.REACTIVATED)
        val state = provisioning.status(app, scope)
        assertThat(state.state?.lifecycle).isEqualTo(Lifecycle.ACTIVE)
        assertThat(state.state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        assertThat(state.state?.bindingRevision).isEqualTo("4")
        assertThat(state.retentionDeadline).isNull()
        assertThat(state.nextActions).isEmpty()
        assertThat(jdbc.queryForObject("select closure_retain_until is null from ums_profile_bindings", Boolean::class.java))
            .isTrue()
        // Closure history lives in lifecycle evidence; the live-closure flag is cleared.
        assertThat(jdbc.queryForObject("select closure_present from ums_profile_bindings", Boolean::class.java)).isFalse()
        assertThat(jdbc.queryForList("select action from ums_profile_lifecycle_evidence order by action", String::class.java))
            .containsExactly("CLOSED", "REACTIVATED")

        val display = editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ)))
        assertThat(display.fields[ProfileField.DISPLAY_NAME.wireName]).isEqualTo("Ada")

        assertThat(eventCount()).isEqualTo(4)
        val event = event(4)
        assertThat(event["reason"].asText()).isEqualTo("REACTIVATED")
        assertThat(event["state"]["lifecycle"].asText()).isEqualTo("ACTIVE")
        assertThat(event["state"]["readiness"].asText()).isEqualTo("PROFILE_READY")
        assertThat(event["state"]["bindingRevision"].asText()).isEqualTo("4")
        assertThat(event["state"].has("displayName")).isFalse()

        val evidence = jdbc.queryForMap("""select action, binding_revision, retain_until from ums_profile_lifecycle_evidence
            where action = 'REACTIVATED'""")
        assertThat(evidence["binding_revision"]).isEqualTo(4L)
        assertThat(evidence["retain_until"]).isNull()

        write("reactivated-event", event.toString())
        write("reactivated-status", mapper.writeValueAsString(state))
    }

    @Test fun `reactivation is refused at or after the retention deadline`() {
        val closedAt = clock.instant()
        close(closedAt)
        clock.advance(Duration.ofDays(90))

        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)
        clock.advance(Duration.ofDays(1))
        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)
        val state = provisioning.status(app, scope)
        assertThat(state.state?.lifecycle).isEqualTo(Lifecycle.CLOSED)
        assertThat(state.state?.bindingRevision).isEqualTo("3")
        assertThat(state.retentionDeadline).isEqualTo(closedAt.plus(Duration.ofDays(90)).toString())
    }

    @Test fun `reactivation is refused while a deletion cause or erasure is in force`() {
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
        close()
        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("DELETION_PENDING")

        jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED', readiness = 'PROFILE_REQUIRED',
            profile_reference = null, generation = null, profile_revision = null, closure_at = null,
            closure_retain_until = null, closure_present = true""")
        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)
    }

    @Test fun `reactivation is refused for an absent profile, a disabled app, and a legacy closure`() {
        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(reactivation.reactivate(TrustedReactivationFact(absent, UUID.randomUUID(), "lifecycle")).outcome)
            .isEqualTo(ReactivationOutcome.UNAVAILABLE)

        jdbc.update("""update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED',
            closure_present = true""")
        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)

        close()
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy::jsonb, '{adopted}', 'false')")
        assertThat(reactivate().outcome).isEqualTo(ReactivationOutcome.UNAVAILABLE)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("CLOSED")
    }

    @Test fun `repeating reactivation is harmless and emits nothing further`() {
        close()
        val first = reactivate()
        val events = eventCount()
        val again = reactivate()

        assertThat(first.outcome).isEqualTo(ReactivationOutcome.REACTIVATED)
        assertThat(again.outcome).isEqualTo(ReactivationOutcome.ALREADY_ACTIVE)
        assertThat(eventCount()).isEqualTo(events)
        assertThat(jdbc.queryForObject("select binding_revision from ums_profile_bindings", Long::class.java)).isEqualTo(4L)
    }

    private fun eventCount(): Int =
        jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)!!

    private fun event(revision: Long) = mapper.readTree(
        jdbc.queryForObject("select payload::text from ums_event_outbox where stream_revision = ?", String::class.java, revision))

    private fun write(name: String, value: String) {
        val directory = Path.of("build", "generated-contract-fixtures")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), value)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
