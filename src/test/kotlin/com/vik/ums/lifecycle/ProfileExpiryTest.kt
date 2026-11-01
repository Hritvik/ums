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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
class ProfileExpiryTest {
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
    private lateinit var reactivation: ProfileReactivationService
    private lateinit var expiry: ProfileExpiryService

    @BeforeEach fun setup() {
        val ds: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_lifecycle_evidence,
            ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests,
            ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts,
            ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts,
            ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
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
        expiry = ProfileExpiryService(jdbc, tx, mapper, "test", clock)
        editing.patch(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.EDIT)),
            ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Ada"), "lifecycle"))
    }

    private fun close(closedAt: Instant = clock.instant()) =
        closure.close(TrustedClosureFact(scope, closedAt, UUID.randomUUID(), "lifecycle"))

    private fun expiryFact(scope: ProfileScope = this.scope) =
        TrustedExpiryFact(scope, UUID.randomUUID(), "lifecycle")

    @Test fun `expiry erases a closed profile at its deadline with one fence and one event`() {
        val closedAt = clock.instant()
        close(closedAt)
        clock.advance(Duration.ofDays(90))

        val result = expiry.expire(expiryFact())

        assertThat(result.outcome).isEqualTo(ExpiryOutcome.EXPIRED)
        assertThat(result.state?.lifecycle).isEqualTo(Lifecycle.ERASED)
        assertThat(result.state?.bindingRevision).isEqualTo("4")
        val row = jdbc.queryForMap("""select lifecycle, profile_reference, generation, profile_revision, display_name,
            avatar_reference, display_locale, display_timezone, closure_present, closure_at, closure_retain_until
            from ums_profile_bindings""")
        assertThat(row["lifecycle"]).isEqualTo("ERASED")
        listOf("profile_reference", "generation", "profile_revision", "display_name",
            "avatar_reference", "display_locale", "display_timezone", "closure_at", "closure_retain_until")
            .forEach { assertThat(row[it]).describedAs(it).isNull() }
        assertThat(row["closure_present"]).isEqualTo(false)

        val fence = jdbc.queryForMap("select * from ums_profile_erasure_obligations")
        assertThat(fence["obligation_kind"]).isEqualTo("CLOSURE_EXPIRY")
        assertThat(fence["erased_binding_revision"]).isEqualTo(4L)
        assertThat(fence["retired_generation"]).isEqualTo(1L)
        assertThat(fence["obligation_at"]).isEqualTo(java.sql.Timestamp.from(closedAt))
        assertThat(fence["subject_reference"]).isEqualTo(scope.subjectReference)
        assertThat(fence["superseded_causes"].toString()).isEqualTo("[]")

        val actions = jdbc.queryForList("select action from ums_profile_lifecycle_evidence order by action", String::class.java)
        assertThat(actions).containsExactly("CLOSED", "EXPIRED")

        assertThat(eventCount()).isEqualTo(4)
        val event = event(4)
        assertThat(event["reason"].asText()).isEqualTo("ERASED")
        assertThat(event["state"]["lifecycle"].asText()).isEqualTo("ERASED")
        assertThat(event["state"]["readiness"].asText()).isEqualTo("PROFILE_REQUIRED")
        assertThat(event["state"]["deletionCauses"].isEmpty).isTrue()
        listOf("profileReference", "generation", "profileRevision", "displayName", "avatarReference")
            .forEach { assertThat(event["state"].has(it)).isFalse() }

        assertThatThrownBy {
            editing.display(TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ)))
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(provisioning.status(app, scope).state).isNull()

        write("expired-event", event.toString())
    }

    @Test fun `expiry does not run before the published deadline`() {
        close()
        clock.advance(Duration.ofDays(90).minusSeconds(1))

        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.NOT_DUE)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo("CLOSED")
        assertThat(jdbc.queryForObject("select display_name from ums_profile_bindings", String::class.java)).isEqualTo("Ada")
        assertThat(eventCount()).isEqualTo(3)
    }

    @Test fun `a profile with an independent deletion obligation is skipped`() {
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 2, "privacy"))
        close()
        clock.advance(Duration.ofDays(90))

        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.SKIPPED)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("DELETION_PENDING")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Int::class.java)).isZero()
        assertThat(eventCount()).isEqualTo(3)
    }

    @Test fun `a later pending deletion cannot extend the published retention`() {
        close()
        clock.advance(Duration.ofDays(80))
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 3, "privacy"))
        clock.advance(Duration.ofDays(10))

        val result = expiry.expire(expiryFact())

        // The closure deadline is earlier than the deletion deadline, so it governs.
        assertThat(result.outcome).isEqualTo(ExpiryOutcome.EXPIRED)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_causes", Int::class.java)).isZero()
        val event = event(5)
        assertThat(event["reason"].asText()).isEqualTo("ERASED")
        assertThat(event["state"]["deletionCauses"].isEmpty).isTrue()
        // The pending request becomes terminal and reports the truth without claiming completion.
        assertThat(jdbc.queryForObject("select status from ums_profile_deletion_requests", String::class.java))
            .isEqualTo("SUPERSEDED")
        val superseded = jdbc.queryForMap("select superseded_causes from ums_profile_erasure_obligations")
        assertThat(superseded["superseded_causes"].toString()).contains("APP_ONLY")
    }

    @Test fun `a reactivated profile is not expired`() {
        close()
        reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle"))
        val events = eventCount()

        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.SKIPPED)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo("ACTIVE")
        assertThat(jdbc.queryForObject("select display_name from ums_profile_bindings", String::class.java)).isEqualTo("Ada")
        assertThat(eventCount()).isEqualTo(events)
    }

    @Test fun `repeated expiry and unrelated scopes are harmless`() {
        close()
        clock.advance(Duration.ofDays(90))
        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.EXPIRED)
        val events = eventCount()

        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.SKIPPED)
        assertThat(eventCount()).isEqualTo(events)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Int::class.java)).isEqualTo(1)

        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(expiry.expire(expiryFact(absent)).outcome).isEqualTo(ExpiryOutcome.UNAVAILABLE)
        assertThat(eventCount()).isEqualTo(events)
    }

    @Test fun `an avatar association blocks expiry instead of dropping the pointer`() {
        close()
        jdbc.update("update ums_profile_bindings set avatar_reference = 'opaque-avatar-reference'")
        clock.advance(Duration.ofDays(90))

        assertThat(expiry.expire(expiryFact()).outcome).isEqualTo(ExpiryOutcome.CONTENT_BLOCKED)
        assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java))
            .isEqualTo("CLOSED")
        assertThat(jdbc.queryForObject("select avatar_reference from ums_profile_bindings", String::class.java))
            .isEqualTo("opaque-avatar-reference")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations", Int::class.java)).isZero()
    }

    @Test fun `a superseded request reports terminal status without claiming completion`() {
        close()
        clock.advance(Duration.ofDays(80))
        val actor = TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10)))
        val accepted = deletion.request(actor, DeletionRequestCommand(UUID.randomUUID(), 3, "privacy"))
        clock.advance(Duration.ofDays(10))
        expiry.expire(expiryFact())

        val status = deletion.status(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.READ_DELETION_STATUS, clock.instant(), true,
            clock.instant().plus(Duration.ofMinutes(10))), accepted.operationReference)

        assertThat(status.status).isEqualTo("SUPERSEDED")
        assertThat(status.cancellationEligible).isFalse()
        assertThat(Instant.parse(status.deadline))
            .isEqualTo(Instant.parse(status.acceptedAt).plus(Duration.ofDays(30)))
    }

    @Test fun `reactivation and expiry serialize without resurrecting or double erasing`() {
        close()
        clock.advance(Duration.ofDays(90))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val expire = pool.submit(Callable { start.await(); expiry.expire(expiryFact()).outcome })
            val reactivate = pool.submit(Callable {
                start.await()
                reactivation.reactivate(TrustedReactivationFact(scope, UUID.randomUUID(), "lifecycle")).outcome
            })
            start.countDown()
            val expired = expire.get(30, TimeUnit.SECONDS)
            val reopened = reactivate.get(30, TimeUnit.SECONDS)
            val lifecycle = jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)
            when {
                expired == ExpiryOutcome.EXPIRED -> {
                    assertThat(lifecycle).isEqualTo("ERASED")
                    assertThat(reopened).isEqualTo(ReactivationOutcome.UNAVAILABLE)
                    assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
                        Int::class.java)).isEqualTo(1)
                }
                else -> {
                    assertThat(expired).isEqualTo(ExpiryOutcome.SKIPPED)
                    assertThat(lifecycle).isEqualTo("ACTIVE")
                    assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
                        Int::class.java)).isZero()
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertThat(eventCount()).isLessThanOrEqualTo(4)
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
