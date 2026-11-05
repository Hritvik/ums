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
import org.testcontainers.postgresql.PostgreSQLContainer
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
import org.springframework.dao.DataIntegrityViolationException

@Testcontainers
class DueDeletionBatchTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val clock = BatchClock(Instant.parse("2026-10-25T00:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var deletion: ProfileDeletionService
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var finalizer: ProfileFinalizationService
    private lateinit var batch: DueDeletionBatchService
    private fun maintenance(target: AppScope = app) = TrustedMaintenanceAuthority(target, UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION)

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts,
            ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts,
            ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts,
            ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
        deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
        batch = DueDeletionBatchService(jdbc, finalizer, clock)
        jdbc.execute("drop trigger if exists batch_test_failure on ums_profile_erasure_obligations")
        jdbc.execute("drop function if exists batch_test_failure()")
    }

    @Test fun `bounded cursor advances through due work`() {
        val first = accept()
        clock.advance(Duration.ofSeconds(1))
        val second = accept()
        jdbc.update("update ums_profile_bindings set avatar_reference='seeded-asset' where subject_reference=?", first.first.subjectReference)
        clock.advance(Duration.ofDays(30).plusSeconds(1))
        val page1 = batch.run(maintenance(), 1, null, "batch")
        assertThat(page1.results).hasSize(1)
        assertThat(page1.results.single().requestReference).isEqualTo(first.second)
        assertThat(page1.results.single().outcome.name).isEqualTo("LOCAL_COMPLETED")
        assertThat(page1.nextCursor).isNotNull()
        val page2 = batch.run(maintenance(), 1, page1.nextCursor, "batch")
        assertThat(page2.cutoff).isEqualTo(page1.cutoff)
        assertThat(page2.results.single().requestReference).isEqualTo(second.second)
        assertThat(page2.results.single().outcome.name).isEqualTo("LOCAL_COMPLETED")
        assertThat(page2.nextCursor).isNull()
        val path = java.nio.file.Path.of("build/generated-contract-fixtures/deletion-batch-result.json")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, mapper.writeValueAsString(page1))
    }

    @Test fun `transient item failure is safe retryable and earlier item remains committed`() {
        val first = accept()
        clock.advance(Duration.ofSeconds(1))
        val second = accept()
        clock.advance(Duration.ofDays(30).plusSeconds(1))
        failureTrigger(second.second, "40001")
        try {
            val result = batch.run(maintenance(), 10, null, "batch")
            assertThat(result.results.map { it.outcome.name }).containsExactly("LOCAL_COMPLETED", "RETRYABLE")
            assertThat(status(first.second)).isEqualTo("LOCAL_COMPLETED")
            assertThat(status(second.second)).isEqualTo("PENDING")
            assertThat(mapper.writeValueAsString(result)).doesNotContain("sensitive-internal", "SQL", "exception", "error")
        } finally { dropFailureTrigger() }
        val retry = batch.run(maintenance(), 10, null, "retry")
        assertThat(retry.results.single().requestReference).isEqualTo(second.second)
        assertThat(retry.results.single().outcome.name).isEqualTo("LOCAL_COMPLETED")
    }

    @Test fun `nontransient integrity failure propagates after prior item commits`() {
        val first = accept()
        clock.advance(Duration.ofSeconds(1))
        val second = accept()
        clock.advance(Duration.ofDays(30).plusSeconds(1))
        failureTrigger(second.second, "23514")
        try {
            assertThatThrownBy { batch.run(maintenance(), 10, null, "batch") }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(status(first.second)).isEqualTo("LOCAL_COMPLETED")
            assertThat(status(second.second)).isEqualTo("PENDING")
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Long::class.java)).isEqualTo(1)
        } finally { dropFailureTrigger() }
    }

    @Test fun `validation rejects ambient transactions limits and wrong or noncanonical cursors`() {
        val request = accept()
        val cutoff = clock.instant().toString()
        val cursor = DeletionBatchCursor(app, cutoff, cutoff, request.second)
        listOf(0, 101).forEach { limit ->
            assertThatThrownBy { batch.run(maintenance(), limit, null, "batch") }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf(cursor.copy(app=app.copy(appReference=UUID.randomUUID())),
            cursor.copy(cutoff=clock.instant().plusSeconds(1).toString()),
            cursor.copy(afterDeadline=clock.instant().plusSeconds(1).toString()),
            cursor.copy(afterDeadline="not-a-date"),
            cursor.copy(afterDeadline="0000-01-01T00:00:00Z"),
            cursor.copy(afterDeadline="-0001-01-01T00:00:00Z"),
            cursor.copy(afterDeadline="+10000-01-01T00:00:00Z"),
            cursor.copy(afterDeadline="2026-10-25T00:00:00.000000001Z"),
            cursor.copy(afterDeadline="2026-10-25T00:00:00+00:00")).forEach { invalid ->
            assertThatThrownBy { batch.run(maintenance(), 1, invalid, "batch") }.isInstanceOf(IllegalArgumentException::class.java)
        }
        transactions.executeWithoutResult {
            assertThatThrownBy { batch.run(maintenance(), 1, null, "batch") }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy { batch.run(maintenance(), 1, null, "bad correlation") }.isInstanceOf(IllegalArgumentException::class.java)
        val empty = batch.run(maintenance(), 100, null, "batch")
        assertThat(empty.results).isEmpty()
        assertThat(empty.nextCursor).isNull()
        assertThat(status(request.second)).isEqualTo("PENDING")
    }

    @Test fun `fixed cutoff continuation excludes later deadlines and each app is isolated`() {
        val first = accept()
        clock.advance(Duration.ofSeconds(1))
        val later = accept()
        clock.advance(Duration.ofDays(30).minusSeconds(1))
        val firstPage = batch.run(maintenance(), 1, null, "batch")
        assertThat(firstPage.results.single().requestReference).isEqualTo(first.second)
        assertThat(firstPage.nextCursor).isNull()
        assertThat(batch.run(maintenance(app.copy(appReference=UUID.randomUUID())), 100, null, "batch").results).isEmpty()
        assertThat(status(later.second)).isEqualTo("PENDING")
        clock.advance(Duration.ofSeconds(1))
        val secondPass = batch.run(maintenance(), 1, null, "batch")
        assertThat(secondPass.results.single().requestReference).isEqualTo(later.second)
    }

    @Test fun `concurrent page runs rely on idempotent finalizers and commit one erasure per request`() {
        repeat(3) { accept() }
        clock.advance(Duration.ofDays(30))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = pool.invokeAll((1..2).map { Callable { batch.run(maintenance(), 100, null, "batch") } }).map { it.get() }
            assertThat(runs.flatMap { it.results }.map { it.outcome.name }).containsOnly("LOCAL_COMPLETED")
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Long::class.java)).isEqualTo(3)
            assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox where event_type='ums.profile.state-changed.v1' and stream_revision=3", Long::class.java)).isEqualTo(3)
        } finally { pool.shutdownNow() }
    }

    @Test fun `corrupt durable cause propagates after earlier independent item commits`() {
        val first = accept()
        clock.advance(Duration.ofSeconds(1))
        val second = accept()
        jdbc.update("update ums_profile_deletion_causes set source_revision=2 where subject_reference=?", second.first.subjectReference)
        clock.advance(Duration.ofDays(30).plusSeconds(1))
        assertThatThrownBy { batch.run(maintenance(), 10, null, "batch") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(status(first.second)).isEqualTo("LOCAL_COMPLETED")
        assertThat(status(second.second)).isEqualTo("PENDING")
    }

    @Test fun `same deadline keyset visits each request once in PostgreSQL UUID order`() {
        repeat(3) { accept() }
        clock.advance(Duration.ofDays(30))
        val expected = jdbc.query("select request_reference from ums_profile_deletion_requests order by deadline, request_reference",
            { rs, _ -> rs.getObject(1, UUID::class.java) })
        val first = batch.run(maintenance(), 1, null, "batch")
        val second = batch.run(maintenance(), 1, first.nextCursor, "batch")
        val third = batch.run(maintenance(), 1, second.nextCursor, "batch")
        assertThat(listOf(first, second, third).flatMap { it.results }.map { it.requestReference }).containsExactlyElementsOf(expected)
        assertThat(third.nextCursor).isNull()
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Long::class.java)).isEqualTo(3)
    }

    private fun status(reference: UUID): String = jdbc.queryForObject(
        "select status from ums_profile_deletion_requests where request_reference=?", String::class.java, reference)!!
    private fun failureTrigger(reference: UUID, code: String) {
        // Generated UUID and fixed SQLSTATE are test-controlled fault injection, never production input.
        jdbc.execute("""create function batch_test_failure() returns trigger language plpgsql as '
            begin if new.obligation_kind = ''APP_DELETION'' and new.cause_reference = ''$reference''::uuid then
            raise exception ''sensitive-internal'' using errcode = ''$code''; end if; return new; end'""")
        jdbc.execute("create trigger batch_test_failure before insert on ums_profile_erasure_obligations for each row execute function batch_test_failure()")
    }
    private fun dropFailureTrigger() {
        jdbc.execute("drop trigger batch_test_failure on ums_profile_erasure_obligations")
        jdbc.execute("drop function batch_test_failure()")
    }

    private fun accept(): Pair<ProfileScope, UUID> {
        val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        provisioning.provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "batch"))
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plusSeconds(60))
        return scope to deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 1, "batch")).operationReference
    }

    private class BatchClock(@Volatile private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
    }
}
