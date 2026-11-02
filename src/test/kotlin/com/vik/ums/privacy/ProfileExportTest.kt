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
class ProfileExportTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val clock = AdjustableClock(Instant.parse("2026-11-01T09:00:00Z"))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var exports: ProfileExportService
    private lateinit var deletion: ProfileDeletionService

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
        assertThat(artifact["lifecycle"].asText()).isEqualTo("ACTIVE")
        assertThat(artifact["profileRevision"].asText()).isEqualTo("1")
        listOf("appSubject", "subjectReference", "email", "phone", "token").forEach {
            assertThat(artifact.has(it)).isFalse()
        }
        assertThat(exports.status(authority(), first.operationReference).status).isEqualTo("LOCAL_COMPLETED")
    }

    @Test fun `a capability downloads exactly once`() {
        val export = exports.request(authority(), UUID.randomUUID())
        val capability = exports.capability(authority(), export.operationReference)

        val artifact = exports.download(capability.token)
        assertThat(artifact.fields["displayName"]).isEqualTo("Ada")

        assertThatThrownBy { exports.download(capability.token) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `a capability expires after five minutes`() {
        val export = exports.request(authority(), UUID.randomUUID())
        val capability = exports.capability(authority(), export.operationReference)
        clock.advance(Duration.ofMinutes(5))

        assertThatThrownBy { exports.download(capability.token) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `an export expires after twenty four hours and drops its payload`() {
        val export = exports.request(authority(), UUID.randomUUID())
        val capability = exports.capability(authority(), export.operationReference)
        clock.advance(Duration.ofHours(24))

        assertThat(exports.status(authority(), export.operationReference).status).isEqualTo("EXPIRED")
        assertThat(jdbc.queryForObject("select artifact is null from ums_profile_exports", Boolean::class.java)).isTrue()
        assertThatThrownBy { exports.download(capability.token) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `restriction invalidates an outstanding export`() {
        val export = exports.request(authority(), UUID.randomUUID())
        val capability = exports.capability(authority(), export.operationReference)
        deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(), PrivacyPurpose.REQUEST_DELETION,
            clock.instant(), true, clock.instant().plus(Duration.ofMinutes(10))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "export"))

        assertThat(exports.status(authority(), export.operationReference).status).isEqualTo("FAILED")
        assertThatThrownBy { exports.download(capability.token) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_exports where artifact is null",
            Int::class.java)).isEqualTo(1)
    }

    @Test fun `weak or stale authority cannot request an export`() {
        assertThatThrownBy { exports.request(authority(strong = false), UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        clock.advance(Duration.ofMinutes(10))
        assertThatThrownBy { exports.request(authority(authenticatedAt = clock.instant().minus(Duration.ofMinutes(10))), UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
