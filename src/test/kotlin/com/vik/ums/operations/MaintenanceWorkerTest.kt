package com.vik.ums.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.ProfileClosureService
import com.vik.ums.lifecycle.TrustedClosureFact
import com.vik.ums.notices.ProfileNoticeService
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.DueDeletionBatchService
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.TrustedPrivacyAuthority
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileProvisioningService
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.TrustedOnboardingFact
import com.vik.ums.recovery.BackupSignals
import com.vik.ums.retention.RetentionSignals
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
class MaintenanceWorkerTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var lease: WorkerLease
    private lateinit var worker: MaintenanceWorker
    private lateinit var deletion: ProfileDeletionService
    private lateinit var alerts: OperationalAlerts
    private val failures = AuthorizationFailures()
    private val retention = RetentionSignals()
    private val backup = BackupSignals()
    private val exports = com.vik.ums.privacy.ExportSweepSignals()
    private val alertSignals = AlertSignals()

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_worker_lease, ums_profile_notice_intents, ums_erasure_journal_feed,
            ums_profile_erasure_obligations, ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox,
            ums_event_outbox, ums_iam_ingestion_quarantine, ums_profile_bindings, ums_input_checkpoints,
            ums_profile_policies cascade""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "worker"))
        jdbc.update("update ums_profile_bindings set display_name = 'Private Name'")
        deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        val finalizer = ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
        val batches = DueDeletionBatchService(jdbc, finalizer, clock)
        val notices = ProfileNoticeService(jdbc, transactions, mapper, "test", clock)
        lease = WorkerLease(jdbc, clock)
        worker = MaintenanceWorker(jdbc, lease, batches, notices, clock)
        alerts = OperationalAlerts(jdbc, clock, failures, retention, backup, exports, alertSignals,
            retentionEnabled = true)
    }

    private fun requestDeletion() {
        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "worker"))
    }

    private fun lifecycle(): String =
        jdbc.queryForObject("select lifecycle from ums_profile_bindings where subject_reference = ?",
            String::class.java, scope.subjectReference)!!

    @Test fun `the worker finalizes due deletions without a manual call`() {
        requestDeletion()
        clock.advance(Duration.ofDays(30))

        val result = worker.run()

        assertThat(result.skipped).isFalse()
        assertThat(result.finalized).isEqualTo(1)
        assertThat(lifecycle()).isEqualTo("ERASED")
    }

    @Test fun `a second worker skips while the lease is held`() {
        requestDeletion()
        clock.advance(Duration.ofDays(30))
        val holder = lease.tryAcquire("maintenance", Duration.ofMinutes(10))

        assertThat(holder).isNotNull()
        assertThat(worker.run().skipped).isTrue()
        assertThat(lifecycle()).isEqualTo("DELETION_PENDING")

        lease.release("maintenance", holder!!)
        assertThat(worker.run().finalized).isEqualTo(1)
    }

    @Test fun `the worker queues retention notices for a closed profile`() {
        ProfileClosureService(jdbc, transactions, mapper, "test", clock)
            .close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "worker"))

        val result = worker.run()

        assertThat(result.noticesScheduled).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_notice_intents",
            Int::class.java)).isEqualTo(2)
        assertThat(worker.run().noticesScheduled).isZero()
    }

    @Test fun `a sweep that never completes alerts after the startup grace`() {
        val late = OperationalAlerts(jdbc, clock, failures, RetentionSignals(), backup, exports, alertSignals,
            retentionEnabled = true, startedAt = clock.instant().minus(Duration.ofHours(3)))

        late.check()

        assertThat(alertSignals.snapshot?.failedRetentionSweeps).isEqualTo(1)
        assertThat(alertSignals.snapshot?.failedExportCleanup).isEqualTo(1)
    }

    @Test fun `a disabled retention sweep is not reported as a failure`() {
        val disabled = OperationalAlerts(jdbc, clock, failures, RetentionSignals(), backup, exports, alertSignals,
            retentionEnabled = false, startedAt = clock.instant().minus(Duration.ofHours(3)))

        disabled.check()

        assertThat(alertSignals.snapshot?.failedRetentionSweeps).isZero()
    }

    @Test fun `the schedulers run the worker and the alerts`() {
        requestDeletion()
        clock.advance(Duration.ofDays(31))

        MaintenanceScheduler(worker).run()
        AlertScheduler(alerts).check()

        assertThat(lifecycle()).isEqualTo("ERASED")
    }

    @Test fun `alerts reflect each threshold with counts only`() {
        requestDeletion()
        clock.advance(Duration.ofDays(31))
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at,
             quarantined_at, failure_class)
            values (?, 'app', 'poison', 1, 't', '{}'::jsonb, ?, ?, 'TRANSPORT_REJECTED')""",
            UUID.randomUUID(), java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()))
        jdbc.update("""insert into ums_iam_ingestion_quarantine
            (quarantine_reference, producer, application_id, payload_digest, failure_class,
             correlation_id, state, received_at)
            values (?, 'iam', 'app', ?, 'SCHEMA_INVALID', 'worker', 'OPEN', ?)""",
            UUID.randomUUID(), "a".repeat(64), java.sql.Timestamp.from(clock.instant()))
        retention.record(com.vik.ums.retention.RetentionReport(listOf(
            com.vik.ums.retention.SweepClassResult("patch_receipts", 1, 0, 1))))
        backup.failed = true
        exports.failed = true
        repeat(5) { failures.record() }

        val scheduler = AlertScheduler(alerts)
        scheduler.check()
        val snapshot = alertSignals.snapshot!!
        scheduler.check()
        val settled = alertSignals.snapshot!!

        assertThat(snapshot.overdueErasures).isEqualTo(1)
        assertThat(snapshot.overdueBeyondObjective).isEqualTo(1)
        assertThat(snapshot.poisonedOutbox).isEqualTo(1)
        assertThat(snapshot.openIngestionQuarantine).isEqualTo(1)
        assertThat(snapshot.failedRetentionSweeps).isEqualTo(1)
        assertThat(snapshot.backupFailed).isTrue()
        assertThat(snapshot.authorizationFailures).isEqualTo(5)
        assertThat(snapshot.newAuthorizationFailures).isEqualTo(5)
        assertThat(snapshot.failedExportCleanup).isEqualTo(1)
        assertThat(settled.newAuthorizationFailures).isZero()
    }

    @Test fun `an unconfigured backup is an alert, not a healthy zero`() {
        backup.configured = false

        alerts.check()

        assertThat(alertSignals.snapshot?.backupFailed).isTrue()
    }

    @Test fun `a lease expires and lets another worker take over`() {
        requestDeletion()
        clock.advance(Duration.ofDays(30))
        val holder = lease.tryAcquire("maintenance", Duration.ofMinutes(10))!!

        assertThat(lease.renew("maintenance", UUID.randomUUID(), Duration.ofMinutes(10))).isFalse()
        assertThat(lease.renew("maintenance", holder, Duration.ofMinutes(10))).isTrue()

        clock.advance(Duration.ofMinutes(11))

        assertThat(lease.tryAcquire("maintenance", Duration.ofMinutes(10))).isNotNull()
    }

    @Test fun `worker settings are validated`() {
        val batches = DueDeletionBatchService(jdbc, ProfileFinalizationService(jdbc, transactions, mapper, "test", clock), clock)
        val notices = ProfileNoticeService(jdbc, transactions, mapper, "test", clock)

        assertThatThrownBy { MaintenanceWorker(jdbc, lease, batches, notices, clock, batchLimit = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MaintenanceWorker(jdbc, lease, batches, notices, clock, maxPages = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MaintenanceWorker(jdbc, lease, batches, notices, clock, maxFinalizations = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `a lease duration must be positive`() {
        assertThatThrownBy { lease.tryAcquire("maintenance", Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `a healthy configured backup and a completed sweep do not alert`() {
        backup.configured = true
        backup.failed = false
        exports.lastCompletedAt = clock.instant()
        retention.record(com.vik.ums.retention.RetentionReport(listOf(
            com.vik.ums.retention.SweepClassResult("patch_receipts", 1, 0, 0))))

        alerts.check()

        assertThat(alertSignals.snapshot?.backupFailed).isFalse()
        assertThat(alertSignals.snapshot?.failedRetentionSweeps).isZero()
        assertThat(alertSignals.snapshot?.failedExportCleanup).isZero()
    }

    @Test fun `the per-app budget still reaches every application`() {
        val otherApp = AppScope(UUID.randomUUID(), UUID.randomUUID())
        val otherScope = ProfileScope(otherApp.issuerEnvironmentRef, otherApp.appReference, UUID.randomUUID())
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(otherApp, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(otherScope, "iam", UUID.randomUUID(), "entry", 1, "worker"))
        requestDeletion()
        deletion.request(TrustedPrivacyAuthority(otherScope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "worker"))
        clock.advance(Duration.ofDays(30))
        val budgeted = MaintenanceWorker(jdbc, lease, DueDeletionBatchService(jdbc,
            ProfileFinalizationService(jdbc, transactions, mapper, "test", clock), clock),
            ProfileNoticeService(jdbc, transactions, mapper, "test", clock), clock,
            batchLimit = 1, maxPages = 5, noticeLimit = 10, maxFinalizations = 1)

        val result = budgeted.run()

        assertThat(result.finalized).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings where lifecycle = 'ERASED'",
            Int::class.java)).isEqualTo(2)
    }

    @Test fun `a content-blocked app is reported blocked without stopping the pass`() {
        requestDeletion()
        clock.advance(Duration.ofDays(30))
        jdbc.update("update ums_profile_bindings set avatar_reference = 'asset'")

        val result = worker.run()

        assertThat(result.blocked).isEqualTo(1)
        assertThat(result.skipped).isFalse()
        assertThat(lifecycle()).isEqualTo("DELETION_PENDING")
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
