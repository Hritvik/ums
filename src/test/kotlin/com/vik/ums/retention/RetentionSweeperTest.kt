package com.vik.ums.retention

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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
class RetentionSweeperTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private val app = AppScope()
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_exports, ums_profile_export_receipts, ums_profile_export_capabilities,
            ums_profile_patch_receipts, ums_profile_privacy_receipts, ums_profile_privacy_audit,
            ums_profile_lifecycle_evidence, ums_event_inbox, ums_event_outbox, ums_iam_onboarding_receipts,
            ums_iam_source_bindings, ums_profile_erasure_obligations,
            ums_profile_deletion_requests, ums_profile_bindings, ums_profile_policies cascade""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
    }

    private fun sweeper(batchSize: Int = 100) = RetentionSweeper(jdbc, transactions, clock, batchSize)

    private fun ago(days: Long): Instant = clock.instant().minus(Duration.ofDays(days))

    private fun receipt(id: UUID = UUID.randomUUID(), acceptedAt: Instant = ago(8)): UUID {
        jdbc.update("""insert into ums_profile_patch_receipts
            (issuer_environment_ref, app_reference, subject_reference, actor_reference, operation,
             idempotency_key, input_fingerprint, accepted_at, profile_reference, applied_revision)
            values (?, ?, ?, ?, 'PROFILE_PATCH', ?, ?, ?, ?, 1)""",
            app.issuer, app.app, app.subject, UUID.randomUUID(), id, "a".repeat(64),
            java.sql.Timestamp.from(acceptedAt), UUID.randomUUID())
        return id
    }

    private fun outbox(publishedAt: Instant?, quarantined: Boolean = false) {
        val id = UUID.randomUUID()
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at,
             published_at, quarantined_at, failure_class)
            values (?, ?, ?, ?, 't', '{}'::jsonb, ?, ?, ?, ?)""",
            id, "app", "s-$id", (System.nanoTime() and 0xFFFF) + 1, java.sql.Timestamp.from(ago(9)),
            publishedAt?.let { java.sql.Timestamp.from(it) },
            if (quarantined) java.sql.Timestamp.from(ago(9)) else null,
            if (quarantined) "TRANSPORT_REJECTED" else null)
    }

    private fun inbox(processedAt: Instant) {
        val id = UUID.randomUUID()
        jdbc.update("""insert into ums_event_inbox
            (producer, event_id, application_id, stream_id, stream_revision, processed_at)
            values ('iam', ?, 'app', ?, 1, ?)""", id, "s-$id", java.sql.Timestamp.from(processedAt))
    }

    private fun export(requestedAt: Instant, withReceipt: Boolean = false): UUID {
        val reference = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_exports
            (export_reference, issuer_environment_ref, app_reference, subject_reference, state,
             requested_at, expires_at)
            values (?, ?, ?, ?, 'EXPIRED', ?, ?)""",
            reference, app.issuer, app.app, app.subject, java.sql.Timestamp.from(requestedAt),
            java.sql.Timestamp.from(requestedAt.plus(Duration.ofHours(24))))
        if (withReceipt) {
            jdbc.update("""insert into ums_profile_export_receipts
                (issuer_environment_ref, app_reference, subject_reference, actor_reference,
                 idempotency_key, input_fingerprint, export_reference)
                values (?, ?, ?, ?, ?, ?, ?)""",
                app.issuer, app.app, app.subject, UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), reference)
        }
        return reference
    }

    private fun evidence(occurredAt: Instant) {
        jdbc.update("""insert into ums_profile_lifecycle_evidence
            (evidence_reference, issuer_environment_ref, app_reference, subject_reference, action,
             binding_revision, effective_at, occurred_at, causation_event_id, correlation_id)
            values (?, ?, ?, ?, 'CLOSED', 1, ?, ?, ?, 'retention')""",
            UUID.randomUUID(), app.issuer, app.app, app.subject, java.sql.Timestamp.from(occurredAt),
            java.sql.Timestamp.from(occurredAt), UUID.randomUUID())
    }

    private fun audit(occurredAt: Instant) {
        val binding = Triple(app.issuer, app.app, UUID.randomUUID())
        jdbc.update("""insert into ums_profile_policies (issuer_environment_ref, app_reference, policy)
            values (?, ?, '{}'::jsonb) on conflict do nothing""", binding.first, binding.second)
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, profile_reference,
             binding_revision, policy_revision, generation, profile_revision, lifecycle, readiness)
            values (?, ?, ?, ?, 1, 1, 1, 1, 'ACTIVE', 'PROFILE_READY') on conflict do nothing""",
            binding.first, binding.second, binding.third, UUID.randomUUID())
        val request = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference,
             profile_reference, generation, operation_revision, status, accepted_at, deadline)
            values (?, ?, ?, ?, ?, 1, 1, 'PENDING', ?, ?)""",
            request, binding.first, binding.second, binding.third, UUID.randomUUID(),
            java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt.plus(Duration.ofDays(30))))
        jdbc.update("""insert into ums_profile_privacy_audit
            (audit_reference, operation_reference, actor_reference, action, binding_revision,
             occurred_at, correlation_id)
            values (?, ?, ?, 'REQUEST_DELETION', 1, ?, 'retention')""",
            UUID.randomUUID(), request, UUID.randomUUID(), java.sql.Timestamp.from(occurredAt))
    }

    private fun erasureObligation() {
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             retired_profile_reference, retired_generation, obligation_at, erased_binding_revision, erased_at)
            values (?, ?, ?, ?, 'APP_DELETION', ?, 1, ?, 1, ?)""",
            UUID.randomUUID(), app.issuer, app.app, app.subject, UUID.randomUUID(),
            java.sql.Timestamp.from(ago(400)), java.sql.Timestamp.from(ago(400)))
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

    @Test fun `command receipts are swept after seven days and newer ones are kept`() {
        receipt(acceptedAt = ago(8))
        receipt(acceptedAt = ago(6))

        val report = sweeper().sweepAll()

        assertThat(count("ums_profile_patch_receipts")).isEqualTo(1)
        assertThat(report.classes.single { it.name == "patch_receipts" }.swept).isEqualTo(1)
    }

    @Test fun `delivered events are swept but unresolved outbox facts are not`() {
        outbox(publishedAt = ago(8))
        outbox(publishedAt = ago(6))
        outbox(publishedAt = null)
        outbox(publishedAt = null, quarantined = true)
        inbox(processedAt = ago(8))
        inbox(processedAt = ago(6))

        sweeper().sweepAll()

        assertThat(count("ums_event_outbox")).isEqualTo(3)
        assertThat(jdbc.queryForObject(
            "select count(*) from ums_event_outbox where published_at is not null", Int::class.java)).isEqualTo(1)
        assertThat(count("ums_event_inbox")).isEqualTo(1)
    }

    @Test fun `an export row lives a day unless a replay receipt still needs it`() {
        export(requestedAt = ago(2))
        val referenced = export(requestedAt = ago(2), withReceipt = true)

        val first = sweeper().sweepAll()

        assertThat(first.classes.single { it.name == "exports" }).isEqualTo(SweepClassResult("exports", 1, 1, 0))
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_exports where export_reference = ?",
            Int::class.java, referenced)).isEqualTo(1)
        assertThat(count("ums_profile_export_receipts")).isEqualTo(1)
    }

    @Test fun `newer exports and live artifacts are untouched`() {
        export(requestedAt = clock.instant().minus(Duration.ofHours(1)))

        val report = sweeper().sweepAll()

        assertThat(report.classes.single { it.name == "exports" }.swept).isZero()
        assertThat(count("ums_profile_exports")).isEqualTo(1)
    }

    @Test fun `expired capabilities are swept`() {
        val reference = export(requestedAt = clock.instant().minus(Duration.ofHours(2)))
        jdbc.update("""insert into ums_profile_export_capabilities
            (capability_reference, export_reference, token_hash, expires_at, requested_at)
            values (?, ?, ?, ?, ?)""",
            UUID.randomUUID(), reference, "c".repeat(64),
            java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(2))),
            java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(2))))

        sweeper().sweepAll()

        assertThat(count("ums_profile_export_capabilities")).isZero()
    }

    @Test fun `lifecycle audit survives the year and older evidence is removed`() {
        evidence(occurredAt = ago(400))
        evidence(occurredAt = ago(100))

        sweeper().sweepAll()

        assertThat(count("ums_profile_lifecycle_evidence")).isEqualTo(1)
    }

    @Test fun `erasure fences and onboarding receipts are reported as retained, never deleted`() {
        erasureObligation()
        jdbc.update("insert into ums_iam_source_bindings (issuer_environment_ref, environment) values (?, 'test')",
            app.issuer)
        jdbc.update("""insert into ums_iam_onboarding_receipts
            (issuer_environment_ref, environment, producer, original_event_id, internal_event_id,
             app_reference, app_registration_id, app_subject, activation_id, activation_version,
             onboarding_version, onboarding_reason, input_fingerprint, payload_fingerprint, accepted_at)
            values (?, 'test', 'iam', 'evt-1', ?, ?, 'reg', 'aps_1', 'act_1', 1, 1, 'FIRST_ENTRY', ?, ?, ?)""",
            app.issuer, UUID.randomUUID(), app.app, "d".repeat(64), "e".repeat(64), java.sql.Timestamp.from(ago(30)))

        val report = sweeper().sweepAll()

        assertThat(count("ums_profile_erasure_obligations")).isEqualTo(1)
        assertThat(count("ums_iam_onboarding_receipts")).isEqualTo(1)
        assertThat(report.classes.single { it.name == "erasure_fences" })
            .isEqualTo(SweepClassResult("erasure_fences", 0, 1, 0))
        assertThat(report.classes.single { it.name == "onboarding_receipts" })
            .isEqualTo(SweepClassResult("onboarding_receipts", 0, 1, 0))
        assertThat(sweeper().rules().filter { it.table != null }.map { it.table })
            .doesNotContain("ums_profile_erasure_obligations", "ums_profile_expiry_fences")
    }

    @Test fun `privacy audit is swept only after a year`() {
        audit(occurredAt = ago(400))
        audit(occurredAt = ago(100))

        sweeper().sweepAll()

        assertThat(count("ums_profile_privacy_audit")).isEqualTo(1)
    }

    @Test fun `an old export is kept until its receipt is gone`() {
        export(requestedAt = ago(8), withReceipt = true)

        val first = sweeper().sweepAll()
        assertThat(first.classes.single { it.name == "exports" }.swept).isZero()
        assertThat(first.classes.single { it.name == "export_receipts" }.swept).isEqualTo(1)
        assertThat(count("ums_profile_exports")).isEqualTo(1)

        val second = sweeper().sweepAll()
        assertThat(second.classes.single { it.name == "exports" }.swept).isEqualTo(1)
        assertThat(count("ums_profile_exports")).isZero()
    }

    @Test fun `a receipt exactly at the boundary is kept`() {
        receipt(acceptedAt = clock.instant().minus(Duration.ofDays(7)))
        receipt(acceptedAt = clock.instant().minus(Duration.ofDays(7)).minus(Duration.ofSeconds(1)))

        sweeper().sweepAll()

        assertThat(count("ums_profile_patch_receipts")).isEqualTo(1)
    }

    @Test fun `a live export and its capability survive`() {
        val reference = export(requestedAt = clock.instant().minus(Duration.ofHours(1)))
        jdbc.update("""insert into ums_profile_export_capabilities
            (capability_reference, export_reference, token_hash, expires_at, consumed_at, requested_at)
            values (?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), reference, "f".repeat(64),
            java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(3))),
            java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(4))),
            java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(5))))

        val report = sweeper().sweepAll()

        assertThat(report.classes.single { it.name == "exports" }.swept).isZero()
        assertThat(count("ums_profile_exports")).isEqualTo(1)
        assertThat(count("ums_profile_export_capabilities")).isEqualTo(1)
    }

    @Test fun `a failing record does not stop the rest of the sweep`() {
        val ids = (1..3).map { receipt() }.toList()
        val failing = ids[1]

        val report = sweeper().sweepWith { sql, args ->
            if (args.last() == failing) throw IllegalStateException("simulated failure")
            jdbc.update(sql, *args.toTypedArray())
        }

        val patch = report.classes.single { it.name == "patch_receipts" }
        assertThat(patch.swept).isEqualTo(2)
        assertThat(patch.failed).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_patch_receipts where idempotency_key = ?",
            Int::class.java, failing)).isEqualTo(1)
    }

    @Test fun `a sweep is bounded per class`() {
        repeat(5) { receipt() }

        val report = sweeper(batchSize = 2).sweepAll()

        assertThat(report.classes.single { it.name == "patch_receipts" }.swept).isEqualTo(2)
        assertThat(count("ums_profile_patch_receipts")).isEqualTo(3)
    }

    @Test fun `the scheduler runs a sweep and publishes counts`() {
        receipt()
        val signals = RetentionSignals()

        RetentionSweepScheduler(sweeper(), signals).sweep()

        assertThat(count("ums_profile_patch_receipts")).isZero()
        assertThat(signals.report?.swept).isEqualTo(1)
        assertThat(signals.report?.failed).isZero()
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private class AppScope(
        val issuer: UUID = UUID.randomUUID(),
        val app: UUID = UUID.randomUUID(),
        val subject: UUID = UUID.randomUUID(),
    )
}
