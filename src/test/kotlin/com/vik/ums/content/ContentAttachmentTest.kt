package com.vik.ums.content

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.GlobalDeletionOutcome
import com.vik.ums.privacy.GlobalFinalFact
import com.vik.ums.privacy.GlobalPendingFact
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.ProfileGlobalDeletionService
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.privacy.TrustedProviderAuthority
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
class ContentAttachmentTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val clock = AdjustableClock(Instant.parse("2026-11-05T09:00:00Z"))
    private val issuer = UUID.randomUUID()
    private val app = UUID.randomUUID()
    private val subject = UUID.randomUUID()
    private val scope = ProfileScope(issuer, app, subject)
    private val reference = "asset_0123456789abcdef"
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var editing: ProfileEditingService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var finalizer: ProfileFinalizationService

    private class StubAuthority(
        var cleanupSucceeds: Boolean = true,
        var verdict: ContentVerdict = ContentVerdict.VALID,
    ) : ContentAuthority {
        val cleaned = mutableListOf<Pair<String, ProfileScope>>()

        override fun check(reference: String, scope: ProfileScope): ContentVerdict = verdict

        override fun requestCleanup(reference: String, scope: ProfileScope): Boolean {
            cleaned += reference to scope
            return cleanupSucceeds
        }
    }

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_association_state, ums_profile_notice_intents, ums_erasure_journal_feed,
            ums_content_cleanup_requests, ums_profile_erasure_obligations,
            ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox,
            ums_event_outbox, ums_iam_ingestion_quarantine, ums_profile_bindings, ums_input_checkpoints,
            ums_profile_policies cascade""")
        transactions = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(AppScope(issuer, app), true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        jdbc.update("insert into ums_iam_source_bindings (issuer_environment_ref, environment) values (?, 'test')", issuer)
        jdbc.update("""insert into ums_iam_app_mappings (issuer_environment_ref, app_registration_id, app_reference)
            values (?, 'dutch-development', ?)""", issuer, app)
        jdbc.update("""insert into ums_iam_subject_mappings
            (issuer_environment_ref, app_reference, app_subject, subject_reference, activation_id)
            values (?, ?, 'aps_0123456789abcdefghijkL', ?, 'act_0123456789abcdef')""", issuer, app, subject)
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "content"))
        editing = ProfileEditingService(jdbc, transactions, mapper, "test",
            ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")), HmacPatchFingerprint(ByteArray(32) { 5 }), clock)
        deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
    }

    private fun revision(): Long = jdbc.queryForObject(
        "select profile_revision from ums_profile_bindings where subject_reference = ?", Long::class.java, subject)!!

    private fun bindingRevision(): Long = jdbc.queryForObject(
        "select binding_revision from ums_profile_bindings where subject_reference = ?", Long::class.java, subject)!!

    private fun attach(attachment: TrustedContentAttachment?) {
        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), revision(), mapOf(ProfileField.AVATAR_REFERENCE to reference), "content"),
            attachment)
    }

    @Test fun `a validated attachment is stored and an explicit clear removes it`() {
        attach(TrustedContentAttachment(reference, scope))

        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo(reference)

        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), revision(), mapOf(ProfileField.AVATAR_REFERENCE to null), "content"))
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isNull()
        assertThat(jdbc.queryForObject("""select content_state || ':' || content_reference from
            ums_content_cleanup_requests""", String::class.java)).isEqualTo("PENDING:$reference")

        val owner = StubAuthority()
        assertThat(ContentCleanupWorker(jdbc, owner).run()).isEqualTo(1)
        assertThat(owner.cleaned.single()).isEqualTo(reference to scope)
        assertThat(jdbc.queryForObject("select content_state from ums_content_cleanup_requests",
            String::class.java)).isEqualTo("CONFIRMED")
    }

    @Test fun `replacing an avatar records cleanup for the previous reference`() {
        attach(TrustedContentAttachment(reference, scope))
        val replacement = "asset_9876543210fedcba"
        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), revision(),
                mapOf(ProfileField.AVATAR_REFERENCE to replacement), "content"),
            TrustedContentAttachment(replacement, scope))

        assertThat(jdbc.queryForList("select content_reference from ums_content_cleanup_requests where content_state = 'PENDING'",
            String::class.java)).containsExactly(reference)
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo(replacement)
    }

    @Test fun `an attachment without a matching trust is refused`() {
        assertThatThrownBy { attach(null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { attach(TrustedContentAttachment("asset_fedcba9876543210", scope)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { attach(TrustedContentAttachment(reference, ProfileScope(issuer, app, UUID.randomUUID()))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `erasure keeps the reference as a cleanup debt and reports it`() {
        attach(TrustedContentAttachment(reference, scope))
        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), revision(), "content"))
        clock.advance(Duration.ofDays(30))
        finalizer.finalize(TrustedMaintenanceAuthority(AppScope(issuer, app), UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "content")

        assertThat(jdbc.queryForObject("""select content_state || ':' || content_reference || ':' || content_obligation
            from ums_profile_erasure_obligations""", String::class.java))
            .isEqualTo("PENDING:$reference:true")
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isNull()
        val report = mapper.readTree(jdbc.queryForObject("""select payload::text from ums_event_outbox
            where event_type = 'ums.profile.association-report.v1' and payload->'payload'->>'lifecycleState' = 'ERASED'
            order by occurred_at desc limit 1""", String::class.java)!!)
        assertThat(report["payload"]["contentObligation"].asBoolean()).isTrue()
    }

    @Test fun `cleanup is confirmed by the owner and a failure stays visible`() {
        attach(TrustedContentAttachment(reference, scope))
        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), revision(), "content"))
        clock.advance(Duration.ofDays(30))
        finalizer.finalize(TrustedMaintenanceAuthority(AppScope(issuer, app), UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "content")

        val failing = StubAuthority(cleanupSucceeds = false)
        assertThat(ContentCleanupWorker(jdbc, failing, maxAttempts = 1).run()).isZero()
        assertThat(jdbc.queryForObject("select content_state from ums_profile_erasure_obligations",
            String::class.java)).isEqualTo("FAILED")
        assertThat(failing.cleaned.single()).isEqualTo(reference to scope)

        jdbc.update("""update ums_profile_erasure_obligations set content_state = 'PENDING', content_attempts = 0""")
        val succeeding = StubAuthority()
        assertThat(ContentCleanupWorker(jdbc, succeeding).run()).isEqualTo(1)
        assertThat(jdbc.queryForObject("select content_state from ums_profile_erasure_obligations",
            String::class.java)).isEqualTo("CONFIRMED")
    }

    @Test fun `a global final blocked by an avatar is released after confirmed cleanup`() {
        attach(TrustedContentAttachment(reference, scope))
        val global = ProfileGlobalDeletionService(jdbc, transactions, mapper, "test", clock)
        val provider = TrustedProviderAuthority(AppScope(issuer, app), UUID.randomUUID())
        global.applyPending(provider, GlobalPendingFact(scope, 1, clock.instant(),
            clock.instant().plus(Duration.ofDays(30)), UUID.randomUUID(), "global"))
        val fact = GlobalFinalFact(scope, 2, clock.instant(), UUID.randomUUID(), "global")

        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo(reference)

        val owner = StubAuthority()
        assertThat(ContentCleanupWorker(jdbc, owner).run()).isEqualTo(1)
        assertThat(owner.cleaned.single()).isEqualTo(reference to scope)
        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || content_state from
            ums_profile_erasure_obligations""", String::class.java)).isEqualTo("IAM_GLOBAL_BLOCKED:CONFIRMED")

        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || content_state || ':' || content_reference
            from ums_profile_erasure_obligations""", String::class.java))
            .isEqualTo("IAM_GLOBAL:CONFIRMED:$reference")
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isNull()
    }

    @Test fun `an app-only erase completes after a blocked global final`() {
        attach(TrustedContentAttachment(reference, scope))
        val global = ProfileGlobalDeletionService(jdbc, transactions, mapper, "test", clock)
        val provider = TrustedProviderAuthority(AppScope(issuer, app), UUID.randomUUID())
        global.applyPending(provider, GlobalPendingFact(scope, 1, clock.instant(),
            clock.instant().plus(Duration.ofDays(30)), UUID.randomUUID(), "global"))
        assertThat(global.finalize(provider, GlobalFinalFact(scope, 2, clock.instant(),
            UUID.randomUUID(), "global")).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)
        // The blocked fence already spent its budget; the app-only erase starts a fresh one.
        jdbc.update("""update ums_profile_erasure_obligations
            set content_state = 'FAILED', content_attempts = 8""")

        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), bindingRevision(), "content"))
        clock.advance(Duration.ofDays(30))

        finalizer.finalize(TrustedMaintenanceAuthority(AppScope(issuer, app), UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "content")
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo("ERASED")
        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || content_state || ':' ||
                content_reference || ':' || content_attempts from ums_profile_erasure_obligations""",
            String::class.java)).isEqualTo("APP_DELETION:PENDING:$reference:0")
    }

    @Test fun `a confirmed debt stays confirmed when the association was cleared out of band`() {
        attach(TrustedContentAttachment(reference, scope))
        val global = ProfileGlobalDeletionService(jdbc, transactions, mapper, "test", clock)
        val provider = TrustedProviderAuthority(AppScope(issuer, app), UUID.randomUUID())
        global.applyPending(provider, GlobalPendingFact(scope, 1, clock.instant(),
            clock.instant().plus(Duration.ofDays(30)), UUID.randomUUID(), "global"))
        val fact = GlobalFinalFact(scope, 2, clock.instant(), UUID.randomUUID(), "global")
        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)
        assertThat(ContentCleanupWorker(jdbc, StubAuthority()).run()).isEqualTo(1)

        jdbc.update("update ums_profile_bindings set avatar_reference = null where subject_reference = ?", subject)
        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(jdbc.queryForObject("""select content_state from ums_profile_erasure_obligations
            where obligation_kind = 'IAM_GLOBAL'""", String::class.java)).isEqualTo("CONFIRMED")
    }

    @Test fun `a replacement avatar needs its own cleanup before the blocked final proceeds`() {
        attach(TrustedContentAttachment(reference, scope))
        val global = ProfileGlobalDeletionService(jdbc, transactions, mapper, "test", clock)
        val provider = TrustedProviderAuthority(AppScope(issuer, app), UUID.randomUUID())
        global.applyPending(provider, GlobalPendingFact(scope, 1, clock.instant(),
            clock.instant().plus(Duration.ofDays(30)), UUID.randomUUID(), "global"))
        val fact = GlobalFinalFact(scope, 2, clock.instant(), UUID.randomUUID(), "global")
        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)

        // The owner confirms the first asset, but a patch that raced the pending transition
        // already replaced it with a new one.
        jdbc.update("update ums_profile_erasure_obligations set content_state = 'CONFIRMED'")
        val replacement = "asset_9876543210fedcba"
        jdbc.update("update ums_profile_bindings set avatar_reference = ? where subject_reference = ?",
            replacement, subject)

        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)
        assertThat(jdbc.queryForObject("""select content_state || ':' || content_reference from
            ums_profile_erasure_obligations""", String::class.java)).isEqualTo("PENDING:$replacement")
    }

    @Test fun `a revoked or foreign association is cleared by the repair sweep`() {
        attach(TrustedContentAttachment(reference, scope))
        val pivot = revision()
        val owner = StubAuthority(verdict = ContentVerdict.UNAVAILABLE)
        fun repair() = ContentAssociationRepair(jdbc, transactions, mapper, "test", owner, clock,
            recheckAfter = Duration.ofHours(1)).run()

        assertThat(repair()).isZero()
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isEqualTo(reference)

        clock.advance(Duration.ofHours(2))
        owner.verdict = ContentVerdict.REVOKED
        assertThat(repair()).isEqualTo(1)
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings where subject_reference = ?",
            String::class.java, subject)).isNull()
        assertThat(jdbc.queryForObject(
            "select profile_revision from ums_profile_bindings where subject_reference = ?", Long::class.java, subject))
            .isEqualTo(pivot + 1)
        assertThat(jdbc.queryForObject("""select count(*) from ums_event_outbox
            where event_type = 'ums.profile.state-changed.v1'
              and payload->>'correlationId' = 'content_repair'""", Int::class.java)).isEqualTo(1)

        attach(TrustedContentAttachment(reference, scope))
        owner.verdict = ContentVerdict.VALID
        assertThat(repair()).isZero()
        // The just-checked association is not queried again on the next pass.
        owner.verdict = ContentVerdict.OWNER_MISMATCH
        assertThat(repair()).isZero()
        clock.advance(Duration.ofHours(2))
        assertThat(repair()).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_content_cleanup_requests",
            Int::class.java)).isZero()
    }

    @Test fun `an exhausted cleanup budget releases the blocked final with a failed debt`() {
        attach(TrustedContentAttachment(reference, scope))
        val global = ProfileGlobalDeletionService(jdbc, transactions, mapper, "test", clock)
        val provider = TrustedProviderAuthority(AppScope(issuer, app), UUID.randomUUID())
        global.applyPending(provider, GlobalPendingFact(scope, 1, clock.instant(),
            clock.instant().plus(Duration.ofDays(30)), UUID.randomUUID(), "global"))
        val fact = GlobalFinalFact(scope, 2, clock.instant(), UUID.randomUUID(), "global")
        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)

        val failing = StubAuthority(cleanupSucceeds = false)
        assertThat(ContentCleanupWorker(jdbc, failing, maxAttempts = 1).run()).isZero()
        assertThat(jdbc.queryForObject("select content_state from ums_profile_erasure_obligations",
            String::class.java)).isEqualTo("FAILED")

        assertThat(global.finalize(provider, fact).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || content_state || ':' || content_obligation
            from ums_profile_erasure_obligations""", String::class.java))
            .isEqualTo("IAM_GLOBAL:FAILED:true")
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
