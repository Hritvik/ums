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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
class ProfileDeletionTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-10-24T00:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var datasource: DataSource
    private lateinit var deletion: ProfileDeletionService
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var editing: ProfileEditingService
    private fun authority(purpose: PrivacyPurpose = PrivacyPurpose.REQUEST_DELETION) =
        TrustedPrivacyAuthority(scope, UUID.randomUUID(), purpose, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        datasource = ds
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests,
            ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox,
            ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "privacy"))
        editing = ProfileEditingService(jdbc, tx, mapper, "test", ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")),
            HmacPatchFingerprint(ByteArray(32) { 8 }), clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
    }

    @Test fun `request immediately restricts profile and exact retry preserves original thirty day deadline`() {
        val actor = authority()
        val cmd = DeletionRequestCommand(UUID.randomUUID(), 1, "privacy")
        val accepted = deletion.request(actor, cmd)
        clock.advance(Duration.ofMinutes(1))
        assertThat(deletion.request(actor, cmd)).isEqualTo(accepted)
        val status = deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)
        assertThat(status.status).isEqualTo("PENDING")
        assertThat(Instant.parse(status.deadline)).isEqualTo(Instant.parse(status.acceptedAt).plus(Duration.ofDays(30)))
        assertThat(status.cancellationEligible).isTrue()
        assertThat(jdbc.queryForObject("select correlation_id from ums_profile_privacy_audit", String::class.java)).isEqualTo("privacy")
        assertThatThrownBy { editing.display(TrustedHumanAuthority(scope, actor.actorReference, setOf(HumanAction.READ))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 2, "privacy")).outcome)
            .isEqualTo(ProvisioningOutcome.RESTRICTED)
        val state = jdbc.queryForObject("select payload::text from ums_event_outbox where stream_revision=2", String::class.java)!!
        assertThat(mapper.readTree(state)["state"]["deletionCauses"]["appOnly"]["deadline"].asText()).isEqualTo(status.deadline)
    }

    @Test fun `cancellation restores readiness from current fields and returns schema-bound safe acknowledgements`() {
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        val actor = authority(PrivacyPurpose.CANCEL_DELETION)
        val command = DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy")
        val cancelled = deletion.cancel(actor, command)
        assertThat(cancelled.operationRevision).isEqualTo("2")
        assertThat(provisioning.status(app, scope).state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        assertThat(provisioning.status(app, scope).state?.profileRevision).isEqualTo("1")
        clock.advance(Duration.ofDays(31))
        val fresh = actor.copy(authenticatedAt = clock.instant(), expiresAt = clock.instant().plusSeconds(60))
        assertThat(deletion.cancel(fresh, command)).isEqualTo(cancelled)
        val status = deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)
        assertThat(status.status).isEqualTo("CANCELLED")
        assertThat(status.bindingRevision).isEqualTo("3")
        assertThat(status.cancellationEligible).isFalse()
        write("deletion-requested-event", event(2))
        write("deletion-cancelled-event", event(3))
        write("deletion-request-status", mapper.writeValueAsString(status))
        write("deletion-accepted", mapper.writeValueAsString(accepted))
    }

    @Test fun `expired requests remain restricted and cannot be cancelled at exact deadline`() {
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        clock.advance(Duration.ofDays(30))
        val status = deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)
        assertThat(status.status).isEqualTo("PENDING")
        assertThat(status.cancellationEligible).isFalse()
        assertThatThrownBy { deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo("DELETION_PENDING")
    }

    @Test fun `fresh authority is required on retries and changed idempotency input conflicts`() {
        val actor = authority()
        val command = DeletionRequestCommand(UUID.randomUUID(), 1, "privacy")
        deletion.request(actor, command)
        assertThatThrownBy { deletion.request(actor, command.copy(expectedVersion = 2)) }.isInstanceOf(IllegalArgumentException::class.java)
        listOf(actor.copy(strong = false), actor.copy(purpose = PrivacyPurpose.CANCEL_DELETION),
            actor.copy(authenticatedAt = clock.instant().plusSeconds(1)), actor.copy(expiresAt = clock.instant()),
            actor.copy(scope = scope.copy(subjectReference = UUID.randomUUID()))).forEach { invalid ->
            assertThatThrownBy { deletion.request(invalid, command) }.isInstanceOf(RuntimeException::class.java)
        }
        clock.advance(Duration.ofMinutes(6))
        assertThatThrownBy { deletion.request(actor, command) }.isInstanceOf(IllegalArgumentException::class.java)
        val statusActor = authority(PrivacyPurpose.READ_DELETION_STATUS)
        assertThatThrownBy { deletion.status(statusActor.copy(scope = scope.copy(subjectReference = UUID.randomUUID())),
            UUID.randomUUID()) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `cancellation preserves independent global deletion cause and closure provenance`() {
        val global = UUID.randomUUID()
        seedGlobal(global)
        jdbc.update("update ums_profile_bindings set closure_present=true")
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy"))
        val state = mapper.readTree(event(3))["state"]
        assertThat(state["lifecycle"].asText()).isEqualTo("DELETION_PENDING")
        assertThat(state["deletionCauses"].has("appOnly")).isFalse()
        assertThat(state["deletionCauses"]["iamGlobal"]["requestReference"].asText()).isEqualTo(global.toString())
        assertThat(state["deletionCauses"]["iamGlobal"]["sourceRevision"].asText()).isEqualTo("41")
        assertThat(jdbc.queryForObject("select closure_present from ums_profile_bindings", Boolean::class.java)).isTrue()
    }

    @Test fun `cancel does not reopen closed profile`() {
        jdbc.update("""update ums_profile_bindings set lifecycle='CLOSED', readiness='PROFILE_REQUIRED',
            closure_present=true, closure_at=now(), closure_retain_until=now() + interval '90 days'""")
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy"))
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo("CLOSED")
        assertThat(jdbc.queryForObject("select closure_present from ums_profile_bindings", Boolean::class.java)).isTrue()
    }

    @Test fun `request transaction rolls back every privacy effect on outbox failure`() {
        jdbc.execute("alter table ums_event_outbox add constraint deletion_outbox_failure check (stream_revision <> 2)")
        try {
            assertThatThrownBy { deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy")) }
                .isInstanceOf(RuntimeException::class.java)
            listOf("ums_profile_deletion_requests", "ums_profile_deletion_causes", "ums_profile_privacy_receipts", "ums_profile_privacy_audit")
                .forEach { assertThat(jdbc.queryForObject("select count(*) from $it", Long::class.java)).isZero() }
            assertThat(provisioning.status(app, scope).state?.bindingRevision).isEqualTo("1")
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint deletion_outbox_failure") }
    }

    @Test fun `concurrent request commands have one durable winner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll((1..2).map { Callable { runCatching {
                deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
            } } }).map { it.get() }
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.count { it.exceptionOrNull() is IllegalStateException }).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_requests", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_privacy_audit", Long::class.java)).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `queued cancellation checks deadline after acquiring application lock`() {
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        clock.advance(Duration.ofDays(30).minusSeconds(1))
        val actor = authority(PrivacyPurpose.CANCEL_DELETION)
        val pool = Executors.newSingleThreadExecutor()
        val tx = TransactionTemplate(DataSourceTransactionManager(datasource))
        try {
            tx.executeWithoutResult {
                jdbc.queryForObject("select policy::text from ums_profile_policies for update", String::class.java)
                val started = CountDownLatch(1)
                val result = pool.submit(Callable { started.countDown(); runCatching {
                    deletion.cancel(actor, DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy"))
                } })
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
                val monitor = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until {
                    monitor.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'", Long::class.java)!! > 0
                }
                clock.advance(Duration.ofSeconds(1))
                // The worker must obtain this transaction's lock before sampling its clock.
                pendingResult = result
            }
            assertThat(pendingResult!!.get(5, TimeUnit.SECONDS).exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        } finally { pool.shutdownNow() }
    }

    @Test fun `durable acceptance normalizes microseconds without moving cancellation deadline`() {
        clock.advance(Duration.ofNanos(123456789))
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        val status = deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference)
        assertThat(status.acceptedAt).isEqualTo("2026-10-24T00:00:00.123456Z")
        clock.set(Instant.parse(status.deadline).minusNanos(1))
        deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy"))
        assertThat(deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference).status).isEqualTo("CANCELLED")
    }

    @Test fun `request versus edit and concurrent cancellation serialize to one winner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val races = pool.invokeAll(listOf(
                Callable { runCatching<Any> { deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy")) } },
                Callable { runCatching<Any> { editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
                    ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Edited"), "privacy")) } },
            )).map { it.get() }
            assertThat(races.count { it.isSuccess }).isEqualTo(1)
            val request = races.mapNotNull { it.getOrNull() as? PrivacyAccepted }.singleOrNull()
                ?: deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
            val revision = jdbc.queryForObject("select binding_revision from ums_profile_bindings", Long::class.java)!!
            val cancellations = pool.invokeAll((1..2).map { Callable { runCatching {
                deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
                    DeletionCancellationCommand(request.operationReference, UUID.randomUUID(), revision, "privacy"))
            } } }).map { it.get() }
            assertThat(cancellations.count { it.isSuccess }).isEqualTo(1)
            assertThat(cancellations.count { it.exceptionOrNull() is IllegalStateException }).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `retired generation cannot replay request or cancellation acknowledgement`() {
        val actor = authority()
        val command = DeletionRequestCommand(UUID.randomUUID(), 1, "privacy")
        val accepted = deletion.request(actor, command)
        val cancelActor = authority(PrivacyPurpose.CANCEL_DELETION)
        val cancel = DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy")
        deletion.cancel(cancelActor, cancel)
        // Test-only future generation fence, not a fabricated re-entry adapter.
        jdbc.update("update ums_profile_bindings set profile_reference=?, generation=2, binding_revision=4", UUID.randomUUID())
        assertThatThrownBy { deletion.request(actor, command) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { deletion.cancel(cancelActor, cancel) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { deletion.status(authority(PrivacyPurpose.READ_DELETION_STATUS), accepted.operationReference) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `cancellation recomputes unmet required fields and privacy stores no personal snapshot`() {
        jdbc.update("update ums_profile_policies set policy=jsonb_set(policy, '{requiredFields}', '[\"displayName\"]'::jsonb)")
        val accepted = deletion.request(authority(), DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        deletion.cancel(authority(PrivacyPurpose.CANCEL_DELETION),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 2, "privacy"))
        val status = provisioning.status(app, scope)
        assertThat(status.state?.lifecycle).isEqualTo(Lifecycle.ACTIVE)
        assertThat(status.state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(status.unmetFields).containsExactly(ProfileField.DISPLAY_NAME)
        listOf("ums_profile_privacy_receipts", "ums_profile_privacy_audit").forEach { table ->
            jdbc.query("select row_to_json(r)::text from $table r", { rs, _ -> rs.getString(1) }).forEach {
                assertThat(it).doesNotContain("display_name", "avatar", "token", "authenticatedAt")
            }
        }
    }

    private var pendingResult: java.util.concurrent.Future<Result<PrivacyAccepted>>? = null
    private fun seedGlobal(reference: UUID) {
        jdbc.update("""insert into ums_profile_deletion_causes values (?, ?, ?, 'IAM_GLOBAL', ?, 41, ?, ?)""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference,
            java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(30))))
        jdbc.update("update ums_profile_bindings set lifecycle='DELETION_PENDING', readiness='PROFILE_REQUIRED'")
    }
    private fun event(revision: Long): String = jdbc.queryForObject(
        "select payload::text from ums_event_outbox where stream_revision = ?", String::class.java, revision)!!
    private fun write(name: String, value: String) {
        val path = java.nio.file.Path.of("build/generated-contract-fixtures/$name.json")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, value)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
        fun set(instant: Instant) { now = instant }
    }
}
