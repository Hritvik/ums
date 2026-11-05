package com.vik.ums.lifecycle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
class ProfileClosureTest {
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

    private fun fact(closedAt: Instant = clock.instant()) =
        TrustedClosureFact(scope, closedAt, UUID.randomUUID(), "lifecycle")

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
            AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "lifecycle"))
        editing = ProfileEditingService(jdbc, tx, mapper, "test", ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")),
            HmacPatchFingerprint(ByteArray(32) { 8 }), clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        closure = ProfileClosureService(jdbc, tx, mapper, "test", clock)
    }

    @Test fun `closure publishes retention, one canonical event, and a restricted status with the deadline`() {
        val closedAt = clock.instant()
        val result = closure.close(fact(closedAt))

        assertThat(result.outcome).isEqualTo(ClosureOutcome.CLOSED)
        assertThat(result.retainUntil).isEqualTo(closedAt.plus(Duration.ofDays(90)))
        val state = provisioning.status(app, scope)
        assertThat(state.state?.lifecycle).isEqualTo(Lifecycle.CLOSED)
        assertThat(state.state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(state.state?.bindingRevision).isEqualTo("2")
        assertThat(state.nextActions).containsExactly("REQUEST_REACTIVATION")
        assertThat(state.retentionDeadline).isEqualTo(closedAt.plus(Duration.ofDays(90)).toString())

        assertThatThrownBy {
            editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ)))
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
                ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Ada"), "lifecycle"))
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(eventCount()).isEqualTo(2)
        val event = event(2)
        assertThat(event["reason"].asText()).isEqualTo("CLOSED")
        assertThat(event["state"]["lifecycle"].asText()).isEqualTo("CLOSED")
        assertThat(event["state"]["readiness"].asText()).isEqualTo("PROFILE_REQUIRED")
        assertThat(event["state"]["deletionCauses"].isEmpty).isTrue()
        listOf("displayName", "avatarReference", "displayLocale", "displayTimezone", "email")
            .forEach { assertThat(event["state"].has(it)).isFalse() }
        assertThat(event["state"]["bindingRevision"].asText()).isEqualTo("2")

        val evidence = jdbc.queryForMap("""select action, binding_revision, effective_at, retain_until, correlation_id
            from ums_profile_lifecycle_evidence""")
        assertThat(evidence["action"]).isEqualTo("CLOSED")
        assertThat(evidence["retain_until"]).isEqualTo(java.sql.Timestamp.from(closedAt.plus(Duration.ofDays(90))))
        assertThat(evidence["correlation_id"]).isEqualTo("lifecycle")

        write("closure-event", event.toString())
        write("closure-status", mapper.writeValueAsString(state))
    }

    @Test fun `repeating the closure for the same authoritative time is harmless`() {
        val closedAt = clock.instant()
        assertThat(closure.close(fact(closedAt)).outcome).isEqualTo(ClosureOutcome.CLOSED)
        val again = closure.close(fact(closedAt))

        assertThat(again.outcome).isEqualTo(ClosureOutcome.ALREADY_CLOSED)
        assertThat(again.retainUntil).isEqualTo(closedAt.plus(Duration.ofDays(90)))
        assertThat(provisioning.status(app, scope).state?.bindingRevision).isEqualTo("2")
        assertThat(eventCount()).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_lifecycle_evidence", Int::class.java)).isEqualTo(1)
    }

    @Test fun `a conflicting closure time is rejected without changing stored state`() {
        closure.close(fact(Instant.parse("2026-10-31T09:00:00Z")))
        assertThatThrownBy { closure.close(fact(Instant.parse("2026-10-30T09:00:00Z"))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(provisioning.status(app, scope).retentionDeadline)
            .isEqualTo(Instant.parse("2027-01-29T09:00:00Z").toString())
        assertThat(eventCount()).isEqualTo(2)
    }

    @Test fun `closure during a pending deletion records retention but keeps the deletion restriction`() {
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 1, "privacy"))
        val closedAt = clock.instant()

        val result = closure.close(fact(closedAt))

        assertThat(result.outcome).isEqualTo(ClosureOutcome.RESTRICTED_RETAINED)
        val state = provisioning.status(app, scope)
        assertThat(state.retentionDeadline).isEqualTo(closedAt.plus(Duration.ofDays(90)).toString())
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("DELETION_PENDING")
        assertThat(eventCount()).isEqualTo(2)
        assertThat(jdbc.queryForObject("""select count(*) from ums_profile_bindings
            where closure_present and closure_retain_until is not null""", Int::class.java)).isEqualTo(1)
    }

    @Test fun `closure is unavailable for an absent or already erased profile`() {
        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(closure.close(TrustedClosureFact(absent, clock.instant(), UUID.randomUUID(), "lifecycle")).outcome)
            .isEqualTo(ClosureOutcome.UNAVAILABLE)

        jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED', readiness = 'PROFILE_REQUIRED',
            profile_reference = null, generation = null, profile_revision = null""")
        assertThat(closure.close(fact()).outcome).isEqualTo(ClosureOutcome.UNAVAILABLE)
        assertThat(eventCount()).isEqualTo(1)
    }

    @Test fun `concurrent closures produce one transition and one event`() {
        val closedAt = clock.instant()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit(Callable {
                    start.await()
                    closure.close(fact(closedAt)).outcome
                })
            }
            start.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(outcomes).containsExactlyInAnyOrder(ClosureOutcome.CLOSED, ClosureOutcome.ALREADY_CLOSED)
        } finally {
            pool.shutdownNow()
        }
        assertThat(eventCount()).isEqualTo(2)
        assertThat(jdbc.queryForObject("select binding_revision from ums_profile_bindings", Long::class.java)).isEqualTo(2L)
    }

    private fun eventCount(): Int =
        jdbc.queryForObject("select count(*) from ums_event_outbox where event_type = 'ums.profile.state-changed.v1'", Int::class.java)!!

    private fun event(revision: Long) = mapper.readTree(
        jdbc.queryForObject("select payload::text from ums_event_outbox where event_type = 'ums.profile.state-changed.v1' and stream_revision = ?", String::class.java, revision))

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
