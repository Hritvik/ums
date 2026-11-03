package com.vik.ums.recovery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.privacy.TrustedPrivacyAuthority
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileProvisioningService
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.TrustedOnboardingFact
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

@Testcontainers
class RecoveryTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private val app = UUID.randomUUID()
    private val issuer = UUID.randomUUID()
    private val subject = UUID.randomUUID()
    private val scope = ProfileScope(issuer, app, subject)
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var deletion: ProfileDeletionService
    private lateinit var finalizer: ProfileFinalizationService

    @TempDir lateinit var directory: Path

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_erasure_reconciliation, ums_erasure_journal_feed, ums_profile_exports, ums_profile_export_receipts,
            ums_profile_export_capabilities, ums_profile_notice_intents, ums_profile_erasure_obligations,
            ums_profile_lifecycle_evidence, ums_profile_privacy_audit, ums_profile_privacy_receipts,
            ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners,
            ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings,
            ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox,
            ums_profile_bindings, ums_input_checkpoints, ums_profile_policies cascade""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(com.vik.ums.profiles.AppScope(issuer, app), true, 1, emptySet(),
                ProfileField.entries.toSet(), "l1", "t1"))
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "recovery"))
        jdbc.update("update ums_profile_bindings set display_name = 'Private Name'")
        deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
    }

    private fun journal() = ErasureJournal(directory.resolve("erasure-journal.jsonl"))

    private fun erasureObligation() {
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             retired_profile_reference, retired_generation, obligation_at, erased_binding_revision, erased_at)
            values (?, ?, ?, ?, 'APP_DELETION', ?, 1, ?, 1, ?)""",
            UUID.randomUUID(), issuer, app, subject, UUID.randomUUID(),
            java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()))
    }

    private fun eraseNow(): UUID {
        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "recovery"))
        return accepted.operationReference
    }

    private fun generation(): Long =
        jdbc.queryForObject("select generation from ums_profile_bindings where subject_reference = ?",
            Long::class.java, subject)!!

    private val nextSequence = java.util.concurrent.atomic.AtomicLong(1)

    private fun entry(generation: Long, profile: UUID = UUID.randomUUID()) = ErasureEntry(
        UUID.randomUUID(), scope, profile, generation, 2, clock.instant(),
        sequence = nextSequence.getAndIncrement())

    @Test fun `the journal round-trips minimized entries and refuses a corrupt line`() {
        val journal = journal()
        val one = entry(generation = 1)
        journal.append(one)
        journal.append(entry(generation = 2))

        val entries = journal.entries()
        assertThat(entries).hasSize(2)
        assertThat(entries.first()).isEqualTo(one)
        assertThat(Files.readString(journal.path)).contains("retiredGeneration").doesNotContain("Private Name")

        Files.writeString(journal.path, "{not json}\n", java.nio.file.StandardOpenOption.APPEND)
        assertThatThrownBy { journal.entries() }.isInstanceOf(ErasureJournalException::class.java)
    }

    @Test fun `a committed erasure is appended and the feed marked done`() {
        val request = eraseNow()
        clock.advance(Duration.ofDays(30))
        finalizer.finalize(TrustedMaintenanceAuthority(com.vik.ums.profiles.AppScope(issuer, app),
            UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION), scope, request, "recovery")
        val journal = journal()

        val appended = ErasureJournalFlusher(ErasureFeed(jdbc), journal).flush()

        assertThat(appended).isEqualTo(1)
        assertThat(journal.entries()).hasSize(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_erasure_journal_feed where appended_at is null",
            Int::class.java)).isZero()
    }

    @Test fun `reconciliation erases a resurrected generation and keeps a newer one`() {
        val generation = generation()
        val profile = jdbc.queryForObject("select profile_reference from ums_profile_bindings where subject_reference = ?",
            UUID::class.java, subject)!!
        val journal = journal()
        journal.append(entry(generation, profile))

        val result = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock).reconcile()

        assertThat(result.erased).isEqualTo(1)
        assertThat(jdbc.queryForObject("""select lifecycle || ':' || (display_name is null) || ':'
                || (profile_reference is null) from ums_profile_bindings where subject_reference = ?""",
            String::class.java, subject)).isEqualTo("ERASED:true:true")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
            Int::class.java)).isEqualTo(1)
        // The cursor means a second boot processes nothing already applied.
        assertThat(RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock).reconcile().entries).isZero()

        // Simulate a restore whose cursor predates the entry: a re-onboarded generation is a
        // deliberate new life, not a restore.
        jdbc.update("delete from ums_erasure_reconciliation")
        jdbc.update("""update ums_profile_bindings set lifecycle = 'ACTIVE', readiness = 'PROFILE_READY',
            profile_reference = ?, generation = ?, profile_revision = 1, display_name = 'New Life'
            where subject_reference = ?""", UUID.randomUUID(), generation + 1, subject)
        val newer = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock).reconcile()

        assertThat(newer.newerGeneration).isEqualTo(1)
        assertThat(jdbc.queryForObject("select display_name from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo("New Life")
    }

    @Test fun `reconciliation reports a scope the database no longer has`() {
        val journal = journal()
        journal.append(entry(generation = 1).copy(
            scope = ProfileScope(issuer, app, UUID.randomUUID())))

        val result = RestoreReconciler(jdbc, transactions, mapper, "test", journal, Clock.systemUTC()).reconcile()

        assertThat(result.missing).isEqualTo(1)
    }

    @Test fun `appending to a foreign file is refused instead of losing evidence`() {
        val foreign = journal().path
        Files.createDirectories(foreign.toAbsolutePath().parent)
        Files.writeString(foreign, "{\"version\":9,\"journal\":\"something else\"}\n")

        assertThatThrownBy { journal().append(entry(generation = 1)) }
            .isInstanceOf(ErasureJournalException::class.java)
    }

    @Test fun `startup fails when pending evidence cannot be journaled`() {
        ErasureFeed(jdbc).record(scope, UUID.randomUUID(), 1, 2, clock.instant(), "APP_DELETION", null)
        val blocked = ErasureJournal(directory.resolve("blocked").resolve("journal.jsonl"))
        blocked.initialise()
        blocked.path.toFile().setWritable(false)
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), blocked)
        try {
            assertThatThrownBy {
                RestoreReconciliationLifecycle(jdbc, blocked, flusher,
                    RestoreReconciler(jdbc, transactions, mapper, "test", blocked, clock),
                    allowMissingEvidence = false).start()
            }.isInstanceOf(IllegalStateException::class.java)
        } finally {
            blocked.path.toFile().setWritable(true)
        }
    }

    @Test fun `a torn trailing line is repaired on the next append`() {
        val journal = journal()
        journal.append(entry(generation = 1))
        Files.writeString(journal.path, """{"version":1,"feedReference":""",
            java.nio.file.StandardOpenOption.APPEND)

        journal.append(entry(generation = 2))

        assertThat(journal.entries()).hasSize(2)
    }

    @Test fun `every pending append is flushed, not just one batch`() {
        repeat(3) { ErasureFeed(jdbc).record(scope, UUID.randomUUID(), 1, 2, clock.instant(), "APP_DELETION", null) }
        val journal = journal()
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), journal, batchSize = 1)

        assertThat(flusher.flushAll()).isEqualTo(3)
        assertThat(journal.entries()).hasSize(3)
        assertThat(jdbc.queryForObject("select count(*) from ums_erasure_journal_feed where appended_at is null",
            Int::class.java)).isZero()
    }

    @Test fun `a failed append leaves the feed row pending and is retried`() {
        ErasureFeed(jdbc).record(scope, UUID.randomUUID(), 1, 2, clock.instant(), "APP_DELETION", null)
        val blocked = ErasureJournal(directory)
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), blocked)

        assertThat(flusher.flush()).isZero()
        assertThat(jdbc.queryForObject("""select attempts || ':' || failure_class from ums_erasure_journal_feed""",
            String::class.java)).isEqualTo("1:JOURNAL_UNAVAILABLE")

        val journal = journal()
        assertThat(ErasureJournalFlusher(ErasureFeed(jdbc), journal).flush()).isEqualTo(1)
        assertThat(journal.entries()).hasSize(1)
    }

    @Test fun `a closure-retained profile is erased and its closure columns are cleared`() {
        com.vik.ums.lifecycle.ProfileClosureService(jdbc, transactions, mapper, "test", clock)
            .close(com.vik.ums.lifecycle.TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "recovery"))
        val journal = journal()
        journal.append(entry(generation()).copy(obligationKind = "CLOSURE_EXPIRY"))

        val result = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock).reconcile()

        assertThat(result.erased).isEqualTo(1)
        assertThat(jdbc.queryForObject("""select lifecycle || ':' || (closure_at is null)
            from ums_profile_bindings where subject_reference = ?""", String::class.java, subject))
            .isEqualTo("ERASED:true")
        assertThat(jdbc.queryForObject("select obligation_kind from ums_profile_erasure_obligations",
            String::class.java)).isEqualTo("CLOSURE_EXPIRY")
    }

    @Test fun `a future or malformed backup status is a failure`() {
        val file = directory.resolve("backup-status.json")
        val signals = BackupSignals()
        val monitor = BackupMonitor(file, clock, signals)

        Files.writeString(file, """{"completedAt":"${clock.instant().plus(Duration.ofHours(3))}","failed":false}""")
        monitor.check()
        assertThat(signals.failed).isTrue()

        Files.writeString(file, """{"completedAt":"${clock.instant()}","failed":"yes"}""")
        monitor.check()
        assertThat(signals.failed).isTrue()

        Files.writeString(file, """{"completedAt":"${clock.instant().minus(Duration.ofHours(27))}","failed":false}""")
        monitor.check()
        assertThat(signals.failed).isTrue()
        assertThat(signals.age).isEqualTo(Duration.ofHours(27))
    }

    @Test fun `a journal shorter than the cursor stops startup`() {
        val journal = journal()
        journal.initialise()
        jdbc.update("""insert into ums_erasure_reconciliation (singleton, last_position) values (true, 3)""")
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), journal)
        val reconciler = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock)

        assertThatThrownBy {
            RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler, allowMissingEvidence = false).start()
        }.hasMessageContaining("shorter than the reconciliation cursor")
    }

    @Test fun `reconciliation withdraws a failed notice instead of leaving it sendable`() {
        jdbc.update("""insert into ums_profile_notice_intents
            (notice_reference, issuer_environment_ref, app_reference, subject_reference, notice_class,
             deadline, dedupe_identity, state, queued_at)
            values (?, ?, ?, ?, 'CLOSURE_EXPIRY_7_DAY', ?, ?, 'FAILED', ?)""",
            UUID.randomUUID(), issuer, app, subject, java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
            "a".repeat(64), java.sql.Timestamp.from(clock.instant()))
        val journal = journal()
        journal.append(entry(generation()))

        RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock).reconcile()

        assertThat(jdbc.queryForObject("select state from ums_profile_notice_intents", String::class.java))
            .isEqualTo("SUPPRESSED")
        assertThat(jdbc.queryForObject("""select payload->>'state' from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1'""", String::class.java)).isEqualTo("SUPPRESSED")
    }

    @Test fun `a missing journal stops startup unless degraded mode is accepted`() {
        // An erasure already happened: a missing journal means the evidence is gone.
        erasureObligation()
        val journal = journal()
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), journal)
        val reconciler = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock)

        assertThatThrownBy {
            RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler, allowMissingEvidence = false).start()
        }.isInstanceOf(IllegalStateException::class.java)

        RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler, allowMissingEvidence = true).start()
        assertThat(Files.exists(journal.path)).isFalse()
    }

    @Test fun `a new deployment bootstraps an empty journal`() {
        jdbc.execute("truncate ums_profile_bindings cascade")
        val journal = journal()
        val flusher = ErasureJournalFlusher(ErasureFeed(jdbc), journal)
        val reconciler = RestoreReconciler(jdbc, transactions, mapper, "test", journal, clock)

        assertThatThrownBy {
            RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler, allowMissingEvidence = false).start()
        }.isInstanceOf(IllegalStateException::class.java)

        RestoreReconciliationLifecycle(jdbc, journal, flusher, reconciler,
            allowMissingEvidence = false, bootstrapJournal = true).start()

        assertThat(Files.exists(journal.path)).isTrue()
        assertThat(journal.entries()).isEmpty()
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Test fun `background schedulers and the missing-evidence warning run`() {
        val journal = journal()
        ErasureJournalScheduler(ErasureJournalFlusher(ErasureFeed(jdbc), journal)).flush()
        BackupMonitorScheduler(BackupMonitor(null, clock, BackupSignals())).check()
        RecoveryConfiguration().missingEvidenceWarning()
            .run(org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>()))
    }

    @Test fun `an unreadable backup status is a failure`() {
        val file = directory.resolve("backup-status.json")
        val signals = BackupSignals()
        val monitor = BackupMonitor(file, clock, signals)

        Files.writeString(file, """{"completedAt":"not-a-time","failed":false}""")
        monitor.check()
        assertThat(signals.failed).isTrue()

        Files.writeString(file, """{"failed":false}""")
        signals.failed = false
        monitor.check()
        assertThat(signals.failed).isTrue()
        assertThat(signals.age).isNull()
    }

    @Test fun `the backup monitor reports age and failure`() {
        val file = directory.resolve("backup-status.json")
        val signals = BackupSignals()
        val monitor = BackupMonitor(file, clock, signals)

        monitor.check()
        assertThat(signals.failed).isTrue()

        Files.writeString(file, """{"completedAt":"${clock.instant().minus(Duration.ofHours(30))}","failed":false}""")
        monitor.check()
        assertThat(signals.failed).isTrue()
        assertThat(signals.age).isEqualTo(Duration.ofHours(30))

        Files.writeString(file, """{"completedAt":"${clock.instant()}","failed":true}""")
        monitor.check()
        assertThat(signals.failed).isTrue()

        Files.writeString(file, """{"completedAt":"${clock.instant().minus(Duration.ofHours(2))}","failed":false}""")
        monitor.check()
        assertThat(signals.age).isEqualTo(Duration.ofHours(2))
        assertThat(signals.failed).isFalse()
    }
}
