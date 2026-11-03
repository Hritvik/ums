package com.vik.ums.notices

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Upgrade from the pre-rework schema, which no empty-database test can exercise. */
@Testcontainers
class MigrationUpgradeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17.6-alpine")
    }

    @Test fun `upgrading a populated V11 database rebinds notice identities to the closure instance`() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("11")).load().migrate()
        val jdbc = JdbcTemplate(ds)
        val issuer = UUID.randomUUID()
        val app = UUID.randomUUID()
        val subject = UUID.randomUUID()
        val noticeReference = UUID.randomUUID()
        val closedAt = Instant.parse("2026-10-31T09:00:00Z")
        val deadline = closedAt.plusSeconds(90L * 86_400)
        jdbc.update("""insert into ums_profile_policies (issuer_environment_ref, app_reference, policy)
            values (?, ?, ?::jsonb)""", issuer, app,
            """{"app":{"issuerEnvironmentRef":"$issuer","appReference":"$app"},"adopted":true,"policyRevision":1,
                "requiredFields":[],"humanEditableFields":[],"supportedLocaleDataVersion":"l1",
                "supportedTimezoneDataVersion":"t1"}""")
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, profile_reference, binding_revision,
             policy_revision, generation, profile_revision, lifecycle, readiness, closure_present,
             closure_at, closure_retain_until)
            values (?, ?, ?, ?, 2, 1, 1, 1, 'CLOSED', 'PROFILE_REQUIRED', true, ?, ?)""",
            issuer, app, subject, UUID.randomUUID(), Timestamp.from(closedAt), Timestamp.from(deadline))
        jdbc.update("""insert into ums_profile_notice_intents
            (notice_reference, issuer_environment_ref, app_reference, subject_reference, notice_class,
             deadline, dedupe_identity, state, queued_at)
            values (?, ?, ?, ?, 'CLOSURE_EXPIRY_14_DAY', ?, ?, 'QUEUED', ?)""",
            noticeReference, issuer, app, subject, Timestamp.from(deadline), "0".repeat(64), Timestamp.from(closedAt))

        Flyway.configure().dataSource(ds).load().migrate()

        val closureReference = jdbc.queryForObject("""select closure_reference from ums_profile_bindings
            where subject_reference = ?""", UUID::class.java, subject)!!
        val intent = jdbc.queryForMap("""select closure_reference, dedupe_identity, notice_revision
            from ums_profile_notice_intents where notice_reference = ?""", noticeReference)
        assertThat(intent["closure_reference"]).isEqualTo(closureReference)
        assertThat(intent["notice_revision"]).isEqualTo(1L)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsBytes(
                listOf(issuer.toString(), app.toString(), subject.toString(),
                    "CLOSURE_EXPIRY_14_DAY", closureReference.toString())))
            .joinToString("") { "%02x".format(it) }
        assertThat(intent["dedupe_identity"]).isEqualTo(expected)
    }
}
