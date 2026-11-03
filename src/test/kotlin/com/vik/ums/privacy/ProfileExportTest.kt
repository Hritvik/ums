package com.vik.ums.privacy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.ProfileClosureService
import com.vik.ums.lifecycle.ProfileExpiryService
import com.vik.ums.lifecycle.TrustedClosureFact
import com.vik.ums.lifecycle.TrustedExpiryFact
import com.vik.ums.profiles.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class ProfileExportTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-11-01T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var exports: ProfileExportService
    private lateinit var deletion: ProfileDeletionService
    private lateinit var finalizer: ProfileFinalizationService
    private lateinit var global: ProfileGlobalDeletionService
    private lateinit var closure: ProfileClosureService
    private lateinit var expiry: ProfileExpiryService

    private fun authority(strong: Boolean = true, authenticatedAt: Instant = clock.instant()) =
        TrustedExportAuthority(scope, UUID.randomUUID(), authenticatedAt, strong,
            authenticatedAt.plus(Duration.ofMinutes(5)))

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_exports, ums_profile_export_receipts, ums_profile_export_capabilities,
            ums_profile_notice_intents, ums_profile_erasure_obligations, ums_profile_lifecycle_evidence,
            ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests,
            ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts,
            ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts,
            ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        ProfileProvisioningService(jdbc, tx, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "export"))
        jdbc.update("update ums_profile_bindings set display_name = 'Ada'")
        exports = ProfileExportService(jdbc, tx, mapper, clock)
        deletion = ProfileDeletionService(jdbc, tx, mapper, "test", clock, Duration.ofMinutes(5))
        finalizer = ProfileFinalizationService(jdbc, tx, mapper, "test", clock)
        global = ProfileGlobalDeletionService(jdbc, tx, mapper, "test", clock)
        closure = ProfileClosureService(jdbc, tx, mapper, "test", clock)
        expiry = ProfileExpiryService(jdbc, tx, mapper, "test", clock)
    }

    private fun ready(): UUID = exports.request(authority(), UUID.randomUUID()).operationReference

    private fun state(): String = jdbc.queryForObject("select state from ums_profile_exports",
        String::class.java)!!

    private fun payloadDropped(): Boolean = jdbc.queryForObject(
        "select artifact is null from ums_profile_exports", Boolean::class.java) == true

    /**
     * A READY row seeded directly, so an invalidation hook can be tested after the profile is
     * already restricted (the public request path would refuse to create one then).
     */
    private fun seedReadyExport(): UUID {
        val reference = UUID.randomUUID()
        val now = clock.instant()
        jdbc.update("""insert into ums_profile_exports
            (export_reference, issuer_environment_ref, app_reference, subject_reference, state,
             requested_at, expires_at, artifact)
            values (?, ?, ?, ?, 'READY', ?, ?, ?::jsonb)""",
            reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            Timestamp.from(now), Timestamp.from(now.plus(Duration.ofHours(24))),
            """{"profileRevision":"1","readiness":"PROFILE_READY","lifecycle":"ACTIVE","fields":{},"generatedAt":"2026-11-01T09:00:00Z"}""")
        return reference
    }

    @Test fun `a request produces a closed artifact and replays idempotently`() {
        val key = UUID.randomUUID()
        val actor = authority()
        val first = exports.request(actor, key)
        val again = exports.request(actor, key)

        assertThat(again.operationReference).isEqualTo(first.operationReference)
        assertThat(first.status).isEqualTo("ACCEPTED")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_exports", Int::class.java)).isEqualTo(1)
        val artifact = mapper.readTree(jdbc.queryForObject(
            "select artifact::text from ums_profile_exports", String::class.java)!!)
        assertThat(artifact["fields"]["displayName"].asText()).isEqualTo("Ada")
        assertThat(artifact["fields"].fieldNames().asSequence().toSet())
            .isEqualTo(setOf("displayName"))
        assertThat(artifact["lifecycle"].asText()).isEqualTo("ACTIVE")
        assertThat(artifact["profileRevision"].asText()).isEqualTo("1")
        listOf("appSubject", "subjectReference", "email", "phone", "token").forEach {
            assertThat(artifact.has(it)).isFalse()
        }
        assertThat(exports.status(actor, first.operationReference).status).isEqualTo("LOCAL_COMPLETED")
    }

    @Test fun `a replay reports the export as it is now, not as it was accepted`() {
        val key = UUID.randomUUID()
        val actor = authority()
        val first = exports.request(actor, key)
        exports.capability(actor, first.operationReference)
        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "export"))

        assertThat(exports.request(actor, key).status).isEqualTo("FAILED")
    }

    @Test fun `a capability downloads exactly once`() {
        val export = ready()
        val capability = exports.capability(authority(), export)

        val artifact = exports.download(scope, capability.token)
        assertThat(artifact.fields["displayName"]).isEqualTo("Ada")

        assertThatThrownBy { exports.download(scope, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `a new capability replaces the previous one`() {
        val export = ready()
        val first = exports.capability(authority(), export)
        val second = exports.capability(authority(), export)

        assertThat(first.token).isNotEqualTo(second.token)
        assertThat(jdbc.queryForObject("""select count(*) from ums_profile_export_capabilities
            where export_reference = ? and consumed_at is null""", Int::class.java, export)).isEqualTo(1)
        assertThatThrownBy { exports.download(scope, first.token) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(exports.download(scope, second.token).fields["displayName"]).isEqualTo("Ada")
    }

    @Test fun `concurrent capability requests leave exactly one live token`() {
        val export = ready()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val tokens = try {
            val futures = (1..2).map {
                pool.submit(Callable { start.await(); exports.capability(authority(), export).token })
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(tokens.toSet()).hasSize(2)
        assertThat(jdbc.queryForObject("""select count(*) from ums_profile_export_capabilities
            where export_reference = ? and consumed_at is null""", Int::class.java, export)).isEqualTo(1)
        assertThat(tokens.count { runCatching { exports.download(scope, it) }.isSuccess }).isEqualTo(1)
    }

    @Test fun `a capability cannot be consumed twice concurrently`() {
        val export = ready()
        val token = exports.capability(authority(), export).token
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val succeeded = try {
            val futures = (1..2).map {
                pool.submit(Callable { start.await(); runCatching { exports.download(scope, token) }.isSuccess })
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(succeeded.count { it }).isEqualTo(1)
    }

    @Test fun `another subject cannot redeem someone else's capability`() {
        val export = ready()
        val capability = exports.capability(authority(), export)
        val other = ProfileScope(scope.issuerEnvironmentRef, scope.appReference, UUID.randomUUID())

        assertThatThrownBy { exports.download(other, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(exports.download(scope, capability.token).fields["displayName"]).isEqualTo("Ada")
    }

    @Test fun `a capability expires after five minutes`() {
        val export = ready()
        val capability = exports.capability(authority(), export)
        clock.advance(Duration.ofMinutes(5))

        assertThatThrownBy { exports.download(scope, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `an export expires after twenty four hours and drops its payload`() {
        val export = ready()
        val capability = exports.capability(authority(), export)
        clock.advance(Duration.ofHours(24))

        assertThat(exports.status(authority(), export).status).isEqualTo("EXPIRED")
        assertThat(payloadDropped()).isTrue()
        assertThatThrownBy { exports.download(scope, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `the sweep expires unattended exports and purges their capabilities`() {
        ready()
        exports.capability(authority(), ready())
        clock.advance(Duration.ofHours(25))

        val sweep = exports.sweep()

        assertThat(sweep.expiredExports).isEqualTo(2)
        assertThat(sweep.purgedCapabilities).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_exports where state = 'EXPIRED'",
            Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_exports where artifact is not null",
            Int::class.java)).isEqualTo(0)
    }

    @Test fun `restriction invalidates an outstanding export`() {
        val export = ready()
        val capability = exports.capability(authority(), export)
        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "export"))

        assertThat(exports.status(authority(), export).status).isEqualTo("FAILED")
        assertThatThrownBy { exports.download(scope, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(state()).isEqualTo("INVALIDATED")
        assertThat(payloadDropped()).isTrue()
    }

    @Test fun `finalization invalidates an outstanding export`() {
        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "export"))
        seedReadyExport()
        clock.advance(Duration.ofDays(30))
        assertThat(state()).isEqualTo("READY")

        finalizer.finalize(TrustedMaintenanceAuthority(app, UUID.randomUUID(),
            MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "export")

        assertThat(state()).isEqualTo("INVALIDATED")
        assertThat(payloadDropped()).isTrue()
    }

    @Test fun `an IAM-global pending fact invalidates an outstanding export`() {
        val export = ready()

        global.applyPending(TrustedProviderAuthority(app, UUID.randomUUID()),
            GlobalPendingFact(scope, 1, clock.instant(), clock.instant().plus(Duration.ofDays(30)),
                UUID.randomUUID(), "export"))

        assertThat(exports.status(authority(), export).status).isEqualTo("FAILED")
        assertThat(payloadDropped()).isTrue()
    }

    @Test fun `closure invalidates an outstanding export`() {
        val export = ready()
        val capability = exports.capability(authority(), export)

        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "export"))

        assertThat(exports.status(authority(), export).status).isEqualTo("FAILED")
        assertThat(payloadDropped()).isTrue()
        assertThatThrownBy { exports.download(scope, capability.token) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `expiry invalidates a retained export`() {
        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "export"))
        seedReadyExport()
        clock.advance(Duration.ofDays(91))
        assertThat(state()).isEqualTo("READY")

        expiry.expire(TrustedExpiryFact(scope, UUID.randomUUID(), "export"))

        assertThat(state()).isEqualTo("INVALIDATED")
        assertThat(payloadDropped()).isTrue()
    }

    @Test fun `a closed profile cannot be exported`() {
        closure.close(TrustedClosureFact(scope, clock.instant(), UUID.randomUUID(), "export"))

        assertThatThrownBy { exports.request(authority(), UUID.randomUUID()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `a capability cannot be issued for a revoked export`() {
        val export = ready()
        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "export"))

        assertThatThrownBy { exports.capability(authority(), export) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `the scheduler sweeps quietly when nothing is due`() {
        val scheduler = ProfileExportSweepScheduler(exports)
        assertThat(ProfileExportSweepScheduler::class.java.getMethod("sweep")
            .getAnnotation(Scheduled::class.java)).isNotNull()
        scheduler.sweep()

        exports.capability(authority(), ready())
        scheduler.sweep()
        assertThat(state()).isEqualTo("READY")
    }

    @Test fun `weak or stale authority cannot request an export`() {
        assertThatThrownBy { exports.request(authority(strong = false), UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        clock.advance(Duration.ofMinutes(10))
        assertThatThrownBy {
            exports.request(authority(authenticatedAt = clock.instant().minus(Duration.ofMinutes(10))), UUID.randomUUID())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            exports.request(authority(authenticatedAt = clock.instant().plusSeconds(1)), UUID.randomUUID())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `an unknown export is unavailable`() {
        assertThatThrownBy { exports.status(authority(), UUID.randomUUID()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { exports.capability(authority(), UUID.randomUUID()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { exports.download(scope, "x".repeat(43)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `a successful export cleanup clears the failure signal`() {
        val signals = ExportSweepSignals()
        signals.failed = true

        ProfileExportSweepScheduler(exports, signals, clock).sweep()

        assertThat(signals.failed).isFalse()
        assertThat(signals.lastCompletedAt).isEqualTo(clock.instant())
    }

    @Test fun `a failing export cleanup is signalled instead of silent`() {
        val unreachable = org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/ums", "ums", "ums")
        val broken = ProfileExportService(JdbcTemplate(unreachable),
            TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(unreachable)),
            mapper, clock)
        val signals = ExportSweepSignals()

        ProfileExportSweepScheduler(broken, signals, clock).sweep()

        assertThat(signals.failed).isTrue()
        assertThat(signals.lastCompletedAt).isNull()
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
