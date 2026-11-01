package com.vik.ums.notices

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.ProfileClosureService
import com.vik.ums.lifecycle.ProfileExpiryService
import com.vik.ums.lifecycle.ProfileReactivationService
import com.vik.ums.lifecycle.TrustedClosureFact
import com.vik.ums.lifecycle.TrustedExpiryFact
import com.vik.ums.lifecycle.TrustedReactivationFact
import com.vik.ums.privacy.DeletionCancellationCommand
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.privacy.TrustedPrivacyAuthority
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
class ProfileNoticeTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-10-31T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var closure: ProfileClosureService
    private lateinit var reactivation: ProfileReactivationService
    private lateinit var expiry: ProfileExpiryService
    private lateinit var finalization: ProfileFinalizationService
    private lateinit var notices: ProfileNoticeService
    private val authority get() = TrustedNoticeAuthority(app, UUID.randomUUID())

    @BeforeEach fun setup() {
        val ds: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_notice_intents, ums_profile_lifecycle_evidence,
            ums_profile_privacy_audit, ums_profile_privacy_receipts,
            ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners,
            ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings,
            ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox, ums_profile_bindings,
            ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(
            AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "lifecycle"))
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        closure = ProfileClosureService(jdbc, tx, mapper, "test", clock)
        reactivation = ProfileReactivationService(jdbc, tx, mapper, "test", clock)
        expiry = ProfileExpiryService(jdbc, tx, mapper, "test", clock)
        finalization = ProfileFinalizationService(jdbc, tx, mapper, "test", clock)
        notices = ProfileNoticeService(jdbc, tx, mapper, "test", clock)
    }

    private fun close(closedAt: Instant = clock.instant()) =
        closure.close(TrustedClosureFact(scope, closedAt, UUID.randomUUID(), "lifecycle"))

    private fun schedule() = notices.schedule(authority, scope, "notice")

    @Test fun `scheduling publishes both windows with deterministic dedupe and no personal data`() {
        close()
        val deadline = clock.instant().plus(Duration.ofDays(90))

        val results = schedule()

        assertThat(results.map { it.outcome }).containsOnly(NoticeOutcome.QUEUED)
        assertThat(results.map { it.noticeClass })
            .containsExactly(NoticeClass.CLOSURE_EXPIRY_14_DAY, NoticeClass.CLOSURE_EXPIRY_7_DAY)
        assertThat(results.map { it.deadline }).containsOnly(deadline)
        val rows = jdbc.queryForList("""select notice_class, dedupe_identity, state, deadline
            from ums_profile_notice_intents order by notice_class""")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it["dedupe_identity"] }).doesNotHaveDuplicates()
        assertThat(rows.map { it["state"] }).containsOnly("QUEUED")
        assertThat(rows.map { it["deadline"] }).containsOnly(java.sql.Timestamp.from(deadline))

        val payloads = jdbc.queryForList("""select payload::text from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1' order by stream_revision""", String::class.java)
        assertThat(payloads).hasSize(2)
        val intent = mapper.readTree(payloads.first())
        assertThat(intent["type"].asText()).isEqualTo("ums.profile.notice-intent.v1")
        assertThat(intent["state"].asText()).isEqualTo("QUEUED")
        assertThat(intent["dedupeIdentity"].asText()).matches("[a-f0-9]{64}")
        assertThat(intent["scope"]["subjectReference"].asText()).isEqualTo(scope.subjectReference.toString())
        listOf("displayName", "email", "avatarReference", "contact").forEach {
            assertThat(intent.has(it)).isFalse()
        }
        write("notice-intent-producer", payloads.first())
    }

    @Test fun `a late observed closure skips a passed window and never shifts the deadline`() {
        close()
        val deadline = clock.instant().plus(Duration.ofDays(90))
        clock.advance(Duration.ofDays(80))

        val results = schedule().associateBy { it.noticeClass }

        assertThat(results[NoticeClass.CLOSURE_EXPIRY_14_DAY]?.outcome).isEqualTo(NoticeOutcome.WINDOW_PASSED)
        assertThat(results[NoticeClass.CLOSURE_EXPIRY_7_DAY]?.outcome).isEqualTo(NoticeOutcome.QUEUED)
        assertThat(results[NoticeClass.CLOSURE_EXPIRY_7_DAY]?.deadline).isEqualTo(deadline)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_notice_intents", Int::class.java)).isEqualTo(2)
    }

    @Test fun `repeat scheduling is idempotent`() {
        close()
        schedule()
        val results = schedule()

        assertThat(results.map { it.outcome }).containsOnly(NoticeOutcome.ALREADY_QUEUED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_notice_intents", Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("""select count(*) from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1'""", Int::class.java)).isEqualTo(2)
    }

    @Test fun `delivery states move only through the allowed order`() {
        close()
        val first = schedule().first { it.noticeClass == NoticeClass.CLOSURE_EXPIRY_14_DAY }.noticeReference!!

        assertThatThrownBy { notices.acknowledge(authority, scope, first, "notice") }
            .isInstanceOf(IllegalArgumentException::class.java)
        notices.markHandedOff(authority, scope, first, "notice")
        assertThat(stateOf(first)).isEqualTo("HANDED_OFF")
        notices.acknowledge(authority, scope, first, "notice")
        assertThat(stateOf(first)).isEqualTo("ACKNOWLEDGED")
        assertThatThrownBy { notices.fail(authority, scope, first, "ADAPTER_UNAVAILABLE", "notice") }
            .isInstanceOf(IllegalArgumentException::class.java)

        val second = schedule().first { it.noticeClass == NoticeClass.CLOSURE_EXPIRY_7_DAY }.noticeReference!!
        notices.fail(authority, scope, second, "ADAPTER_UNAVAILABLE", "notice")
        assertThat(stateOf(second)).isEqualTo("FAILED")
        assertThat(jdbc.queryForObject("select failure_class from ums_profile_notice_intents where notice_reference = ?",
            String::class.java, second)).isEqualTo("ADAPTER_UNAVAILABLE")
        notices.markHandedOff(authority, scope, second, "notice")
        assertThat(stateOf(second)).isEqualTo("HANDED_OFF")
        assertThatThrownBy { notices.fail(authority, scope, second, "Bad Class With Spaces", "notice") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `reactivation suppresses outstanding intents and a later closure gets fresh ones`() {
        close()
        schedule()
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))

        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")
        assertThat(schedule().map { it.outcome }).containsOnly(NoticeOutcome.NOT_CLOSED)

        clock.advance(Duration.ofDays(1))
        close()
        assertThat(schedule().map { it.outcome }).containsOnly(NoticeOutcome.QUEUED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_notice_intents", Int::class.java)).isEqualTo(4)
    }

    @Test fun `erasure by expiry or app-only finalization suppresses outstanding intents`() {
        close()
        schedule()
        clock.advance(Duration.ofDays(90))
        expiry.expire(TrustedExpiryFact(scope, UUID.randomUUID(), "lifecycle"))
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")

        jdbc.execute("truncate ums_profile_notice_intents")
        val otherScope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        provisioning.provision(TrustedOnboardingFact(otherScope, "iam-test", UUID.randomUUID(), "entry", 1, "lifecycle"))
        closure.close(TrustedClosureFact(otherScope, clock.instant(), UUID.randomUUID(), "lifecycle"))
        notices.schedule(TrustedNoticeAuthority(app, UUID.randomUUID()), otherScope, "notice")
        val actor = TrustedPrivacyAuthority(otherScope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
        clock.advance(Duration.ofDays(30))
        finalization.finalize(TrustedMaintenanceAuthority(app, UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION),
            otherScope, accepted.operationReference, "lifecycle")
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")
    }

    @Test fun `active or absent profiles have nothing to schedule`() {
        assertThat(schedule().map { it.outcome }).containsOnly(NoticeOutcome.NOT_CLOSED)
        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(notices.schedule(authority, absent, "notice").map { it.outcome })
            .containsOnly(NoticeOutcome.UNAVAILABLE)
    }

    @Test fun `re-closing at the same authoritative time still produces fresh notices`() {
        val closedAt = clock.instant()
        close(closedAt)
        schedule()
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))

        // Same authoritative closure time: harmlessly idempotent for the binding, but a new
        // closure instance, so the suppressed intents must not swallow the new ones.
        close(closedAt)

        assertThat(schedule().map { it.outcome }).containsOnly(NoticeOutcome.QUEUED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_notice_intents", Int::class.java)).isEqualTo(4)
    }

    @Test fun `an earlier deletion obligation suppresses closed-profile intents and says so`() {
        close()
        schedule()
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))

        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))

        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")
        assertThat(jdbc.queryForObject("""select count(*) from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1'
            and payload ->> 'state' = 'SUPPRESSED'""", Int::class.java)).isEqualTo(2)
        write("notice-suppressed-producer", jdbc.queryForObject("""select payload::text from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1'
            and payload ->> 'state' = 'SUPPRESSED' order by stream_revision limit 1""", String::class.java)!!)
    }

    @Test fun `a late delivery after suppression is recorded instead of failing`() {
        close()
        val reference = schedule().first().noticeReference!!
        notices.markHandedOff(authority, scope, reference, "notice")
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))

        notices.acknowledge(authority, scope, reference, "notice")

        val row = jdbc.queryForMap("""select state, delivered_after_suppression from ums_profile_notice_intents
            where notice_reference = ?""", reference)
        assertThat(row["state"]).isEqualTo("ACKNOWLEDGED")
        assertThat(row["delivered_after_suppression"]).isEqualTo(true)
    }

    @Test fun `a notice is queued exactly at its window start`() {
        close()
        clock.advance(Duration.ofDays(76))

        val result = schedule().first { it.noticeClass == NoticeClass.CLOSURE_EXPIRY_14_DAY }

        assertThat(result.outcome).isEqualTo(NoticeOutcome.QUEUED)
    }

    @Test fun `cancelling a deletion re-arms the retention notices`() {
        close()
        schedule()
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")

        val cancelling = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.CANCEL_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.cancel(cancelling, DeletionCancellationCommand(accepted.operationReference,
            UUID.randomUUID(), 3, "privacy"))

        // The retention obligation is live again, so the owner must be told the notices are back.
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("QUEUED")
        assertThat(jdbc.queryForObject("""select max((payload ->> 'noticeRevision')::bigint) from ums_event_outbox
            where event_type = 'ums.profile.notice-intent.v1' and payload ->> 'state' = 'QUEUED'""",
            Long::class.java)).isEqualTo(3L)
    }

    @Test fun `app-only erasure suppresses intents that outlived the request`() {
        close()
        schedule()
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
        // A concurrent re-arm (for example after a cancelled retry) leaves them live again.
        jdbc.update("update ums_profile_notice_intents set state = 'QUEUED'")
        clock.advance(Duration.ofDays(30))

        finalization.finalize(TrustedMaintenanceAuthority(app, UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "lifecycle")

        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")
    }

    private fun stateOf(reference: UUID): String =
        jdbc.queryForObject("select state from ums_profile_notice_intents where notice_reference = ?",
            String::class.java, reference)!!

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
