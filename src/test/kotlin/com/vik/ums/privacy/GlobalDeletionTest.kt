package com.vik.ums.privacy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.ProfileClosureService
import com.vik.ums.lifecycle.ProfileExpiryService
import com.vik.ums.lifecycle.ProfileReactivationService
import com.vik.ums.lifecycle.TrustedReactivationFact
import com.vik.ums.lifecycle.TrustedClosureFact
import com.vik.ums.lifecycle.TrustedExpiryFact
import com.vik.ums.notices.ProfileNoticeService
import com.vik.ums.notices.TrustedNoticeAuthority
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
import javax.sql.DataSource

@Testcontainers
class GlobalDeletionTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-10-31T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var editing: ProfileEditingService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var closure: ProfileClosureService
    private lateinit var expiry: ProfileExpiryService
    private lateinit var reactivation: ProfileReactivationService
    private lateinit var notices: ProfileNoticeService
    private lateinit var global: ProfileGlobalDeletionService
    private val provider get() = TrustedProviderAuthority(app, UUID.randomUUID())

    @BeforeEach fun setup() {
        val ds: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_notice_intents, ums_profile_erasure_obligations,
            ums_profile_lifecycle_evidence, ums_profile_privacy_audit, ums_profile_privacy_receipts,
            ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners,
            ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings,
            ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox, ums_profile_bindings,
            ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(
            AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "global"))
        editing = ProfileEditingService(jdbc, tx, mapper, "test", ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")),
            HmacPatchFingerprint(ByteArray(32) { 8 }), clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        closure = ProfileClosureService(jdbc, tx, mapper, "test", clock)
        expiry = ProfileExpiryService(jdbc, tx, mapper, "test", clock)
        reactivation = ProfileReactivationService(jdbc, tx, mapper, "test", clock)
        notices = ProfileNoticeService(jdbc, tx, mapper, "test", clock)
        global = ProfileGlobalDeletionService(jdbc, tx, mapper, "test", clock)
    }

    private fun pending(sourceRevision: Long, deadline: Instant = clock.instant().plus(Duration.ofDays(20))) =
        global.applyPending(provider, GlobalPendingFact(scope, sourceRevision, clock.instant(), deadline,
            UUID.randomUUID(), "global"))

    private fun cancel(sourceRevision: Long) =
        global.cancel(provider, GlobalCancellationFact(scope, sourceRevision, clock.instant(), UUID.randomUUID(), "global"))

    private fun final(sourceRevision: Long) =
        global.finalize(provider, GlobalFinalFact(scope, sourceRevision, clock.instant(), UUID.randomUUID(), "global"))

    @Test fun `global pending restricts immediately and records the provider deadline`() {
        val deadline = clock.instant().plus(Duration.ofDays(20))

        val result = pending(1, deadline)

        assertThat(result.outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.DELETION_PENDING)
        assertThat(result.state?.bindingRevision).isEqualTo("2")
        assertThat(result.state?.deletionCauses?.iamGlobal?.deadline).isEqualTo(deadline.toString())
        assertThat(result.state?.deletionCauses?.appOnly).isNull()
        assertThatThrownBy {
            editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ)))
        }.isInstanceOf(IllegalStateException::class.java)
        val event = mapper.readTree(jdbc.queryForObject(
            "select payload::text from ums_event_outbox where stream_revision = 2", String::class.java)!!)
        assertThat(event["reason"].asText()).isEqualTo("DELETION_REQUESTED")
        assertThat(event["state"]["deletionCauses"]["iamGlobal"]["deadline"].asText()).isEqualTo(deadline.toString())
        assertThat(event["state"]["deletionCauses"].has("appOnly")).isFalse()
    }

    @Test fun `cancelling the global cause leaves an app-only obligation in force`() {
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        pending(1, clock.instant().plus(Duration.ofDays(20)))

        val result = cancel(2)

        assertThat(result.outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.DELETION_PENDING)
        assertThat(result.state?.deletionCauses?.appOnly).isNotNull()
        assertThat(result.state?.deletionCauses?.iamGlobal).isNull()
        assertThat(result.state?.bindingRevision).isEqualTo("4")
    }

    @Test fun `cancelling the app-only cause leaves the global obligation in force`() {
        pending(1, clock.instant().plus(Duration.ofDays(20)))
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))

        deletion.cancel(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.CANCEL_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionCancellationCommand(accepted.operationReference, UUID.randomUUID(), 3, "privacy"))

        val state = provisioning.status(app, scope)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("DELETION_PENDING")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes where cause_type = 'IAM_GLOBAL'",
            Int::class.java)).isEqualTo(1)
        assertThat(state.state).isNull()
    }

    @Test fun `cancelling the only global cause restores the closed profile and re-arms notices`() {
        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "lifecycle"))
        notices.schedule(TrustedNoticeAuthority(app, UUID.randomUUID()), scope, "notice")
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("QUEUED")
        pending(1, clock.instant().plus(Duration.ofDays(20)))
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("SUPPRESSED")

        val result = cancel(2)

        assertThat(result.outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.CLOSED)
        assertThat(result.state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("QUEUED")
    }

    @Test fun `authoritative final erases immediately and supersedes a pending request`() {
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))

        val result = final(1)

        assertThat(result.outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.ERASED)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("ERASED")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("select status from ums_profile_deletion_requests where request_reference = ?",
            String::class.java, accepted.operationReference)).isEqualTo("SUPERSEDED")
        val fence = jdbc.queryForMap("select * from ums_profile_erasure_obligations")
        assertThat(fence["obligation_kind"]).isEqualTo("IAM_GLOBAL")
        assertThat(fence["superseded_causes"].toString()).contains("APP_ONLY")
        val evidence = jdbc.queryForList("select action from ums_profile_lifecycle_evidence", String::class.java)
        assertThat(evidence).contains("GLOBAL_ERASED")
        val event = mapper.readTree(jdbc.queryForObject(
            "select payload::text from ums_event_outbox where stream_revision = 3", String::class.java)!!)
        assertThat(event["reason"].asText()).isEqualTo("ERASED")
        assertThat(event["state"]["deletionCauses"].isEmpty).isTrue()
    }

    @Test fun `cancelling a global obligation cannot resurrect a dead closure`() {
        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "lifecycle"))
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))
        pending(1, clock.instant().plus(Duration.ofDays(20)))

        val result = cancel(2)

        // The profile was reactivated before the pending fact, so the sticky closure flag
        // must not drive it back to CLOSED with no live deadline.
        assertThat(result.outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.ACTIVE)
        assertThat(result.state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        val state = provisioning.status(app, scope)
        assertThat(state.retentionDeadline).isNull()
        assertThat(state.nextActions).isEmpty()
        assertThat(editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ))))
            .isNotNull()
    }

    @Test fun `a replayed pending fact is stale and leaves the applied revision untouched`() {
        pending(1)
        val revision = jdbc.queryForObject("select global_source_revision from ums_profile_bindings", Long::class.java)
        val causes = jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Int::class.java)
        val events = jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)

        assertThat(pending(1).outcome).isEqualTo(GlobalDeletionOutcome.STALE)

        assertThat(jdbc.queryForObject("select global_source_revision from ums_profile_bindings", Long::class.java))
            .isEqualTo(revision)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Int::class.java))
            .isEqualTo(causes)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)).isEqualTo(events)
    }

    @Test fun `concurrent finals erase once and the loser is stale`() {
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit(java.util.concurrent.Callable {
                    start.await()
                    final(1).outcome
                })
            }
            start.countDown()
            val outcomes = results.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(outcomes).containsExactlyInAnyOrder(GlobalDeletionOutcome.APPLIED, GlobalDeletionOutcome.STALE)
        } finally {
            pool.shutdownNow()
        }
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Int::class.java))
            .isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)).isEqualTo(2)
    }

    @Test fun `stale and replayed revisions change nothing`() {
        assertThat(pending(1).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(cancel(1).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
        assertThat(cancel(2).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(final(2).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
        assertThat(pending(2).outcome).isEqualTo(GlobalDeletionOutcome.STALE)

        assertThat(final(3).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        val events = jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)

        assertThat(pending(3).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
        assertThat(cancel(3).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
        assertThat(final(4).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Int::class.java)).isEqualTo(events)
    }

    @Test fun `a later global obligation does not withdraw retention notices`() {
        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "lifecycle"))
        notices.schedule(TrustedNoticeAuthority(app, UUID.randomUUID()), scope, "notice")

        pending(1, clock.instant().plus(Duration.ofDays(120)))

        assertThat(jdbc.queryForList("select state from ums_profile_notice_intents", String::class.java))
            .containsOnly("QUEUED")
        clock.advance(Duration.ofDays(90))
        val erased = expiry.expire(TrustedExpiryFact(scope, UUID.randomUUID(), "lifecycle"))
        assertThat(erased.outcome.name).isEqualTo("EXPIRED")
        assertThat(jdbc.queryForObject("select obligation_kind from ums_profile_erasure_obligations",
            String::class.java)).isEqualTo("CLOSURE_EXPIRY")
        assertThat(jdbc.queryForMap("select superseded_causes from ums_profile_erasure_obligations")
            ["superseded_causes"].toString()).contains("IAM_GLOBAL")
    }

    @Test fun `absent, erased, and content-blocked finalizations are refused`() {
        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(global.finalize(provider, GlobalFinalFact(absent, 1, clock.instant(), UUID.randomUUID(), "global")).outcome)
            .isEqualTo(GlobalDeletionOutcome.UNAVAILABLE)

        jdbc.update("update ums_profile_bindings set avatar_reference = 'opaque-avatar-reference'")
        assertThat(final(1).outcome).isEqualTo(GlobalDeletionOutcome.CONTENT_BLOCKED)
        assertThat(jdbc.queryForList("select action from ums_profile_lifecycle_evidence", String::class.java))
            .contains("GLOBAL_FINAL_BLOCKED")
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("ACTIVE")
        jdbc.update("update ums_profile_bindings set avatar_reference = null")

        assertThat(final(1).outcome).isEqualTo(GlobalDeletionOutcome.APPLIED)
        assertThat(pending(1).outcome).isEqualTo(GlobalDeletionOutcome.STALE)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
