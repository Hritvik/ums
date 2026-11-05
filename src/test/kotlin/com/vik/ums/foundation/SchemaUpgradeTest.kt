package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The schema is upgraded while it holds real data. Every earlier state must reach the current
 * one, data must survive, and an interrupted migration must be detectable and resumable.
 */
@Testcontainers
class SchemaUpgradeTest {
    companion object {
        const val LATEST = "24"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine")
    }

    private fun database(name: String): JdbcTemplate {
        postgres.execInContainer("sh", "-c",
            "PGPASSWORD='${postgres.password}' psql -U ${postgres.username} -d postgres -c 'drop database if exists $name'")
        postgres.execInContainer("sh", "-c",
            "PGPASSWORD='${postgres.password}' psql -U ${postgres.username} -d postgres -c 'create database $name'")
        return JdbcTemplate(DriverManagerDataSource(
            "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/$name",
            postgres.username, postgres.password))
    }

    private fun flyway(jdbc: JdbcTemplate): Flyway = Flyway.configure()
        .dataSource(jdbc.dataSource!!)
        .load()

    @Test fun `every earlier schema state upgrades to the current one`() {
        val reachable = (1 until LATEST.toInt()).map { it.toString() }
        for (version in reachable) {
            val jdbc = database("upgrade_$version")
            Flyway.configure().dataSource(jdbc.dataSource!!).target(version).load().migrate()

            val result = flyway(jdbc).migrate()

            assertThat(result.success).isTrue()
            assertThat(flyway(jdbc).info().current()?.version?.version).isEqualTo(LATEST)
            assertThat(flyway(jdbc).info().pending()).isEmpty()
            assertThat(flyway(jdbc).validateWithResult().validationSuccessful).isTrue()
        }
    }

    @Test fun `an upgrade keeps an erased tombstone, its fence, its cause, and a pending request`() {
        val jdbc = database("upgrade_data")
        // Seed the pre-consolidation shape at V11, so V12 has to carry the fence into the
        // obligation table rather than the test passing on a no-op migration.
        Flyway.configure().dataSource(jdbc.dataSource!!).target("11").load().migrate()
        val issuer = UUID.randomUUID()
        val app = UUID.randomUUID()
        val erasedSubject = UUID.randomUUID()
        val pendingSubject = UUID.randomUUID()
        val retiredProfile = UUID.randomUUID()
        val erasedAt = java.time.Instant.parse("2026-10-01T09:00:00Z")
        val acceptedAt = erasedAt.minusSeconds(720 * 3600)
        val pendingAccepted = java.time.Instant.parse("2026-10-20T09:00:00Z")
        val erasedRequest = UUID.randomUUID()
        val pendingRequest = UUID.randomUUID()
        val transactions = org.springframework.transaction.support.TransactionTemplate(
            org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!))
        com.vik.ums.profiles.ProfilePolicyRegistry(jdbc, transactions,
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()).register(
            com.vik.ums.profiles.AppProfilePolicy(com.vik.ums.profiles.AppScope(issuer, app), true, 1,
                emptySet(), com.vik.ums.profiles.ProfileField.entries.toSet(), "l1", "t1"))
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, binding_revision, policy_revision,
             lifecycle, readiness, closure_present)
            values (?, ?, ?, 3, 1, 'ERASED', 'PROFILE_REQUIRED', false)""", issuer, app, erasedSubject)
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, profile_reference, binding_revision,
             policy_revision, generation, profile_revision, lifecycle, readiness, closure_present)
            values (?, ?, ?, ?, 2, 1, 2, 2, 'DELETION_PENDING', 'PROFILE_REQUIRED', false)""",
            issuer, app, pendingSubject, UUID.randomUUID())
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference, profile_reference,
             generation, operation_revision, status, accepted_at, deadline)
            values (?, ?, ?, ?, ?, 2, 2, 'LOCAL_COMPLETED', ?, ?)""",
            erasedRequest, issuer, app, erasedSubject, retiredProfile, java.sql.Timestamp.from(acceptedAt),
            java.sql.Timestamp.from(acceptedAt.plusSeconds(720 * 3600)))
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference, profile_reference,
             generation, operation_revision, status, accepted_at, deadline)
            select ?, issuer_environment_ref, app_reference, subject_reference, profile_reference,
                generation, 1, 'PENDING', ?, ?
            from ums_profile_bindings where subject_reference = ?""",
            pendingRequest, java.sql.Timestamp.from(pendingAccepted),
            java.sql.Timestamp.from(pendingAccepted.plusSeconds(720 * 3600)), pendingSubject)
        jdbc.update("""insert into ums_profile_deletion_causes
            (issuer_environment_ref, app_reference, subject_reference, cause_type, request_reference,
             source_revision, accepted_at, deadline)
            values (?, ?, ?, 'APP_ONLY', ?, 1, ?, ?)""",
            issuer, app, pendingSubject, pendingRequest, java.sql.Timestamp.from(pendingAccepted),
            java.sql.Timestamp.from(pendingAccepted.plusSeconds(720 * 3600)))
        jdbc.update("""insert into ums_profile_erasure_fences
            (issuer_environment_ref, app_reference, subject_reference, request_reference,
             retired_profile_reference, retired_generation, erased_binding_revision)
            values (?, ?, ?, ?, ?, 2, 3)""", issuer, app, erasedSubject, erasedRequest, retiredProfile)
        val expiredProfile = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_expiry_fences
            (fence_reference, issuer_environment_ref, app_reference, subject_reference,
             retired_profile_reference, retired_generation, closure_at, erased_binding_revision, erased_at)
            values (?, ?, ?, ?, ?, 4, ?, 5, ?)""",
            UUID.randomUUID(), issuer, app, UUID.randomUUID(), expiredProfile,
            java.sql.Timestamp.from(acceptedAt), java.sql.Timestamp.from(erasedAt))

        flyway(jdbc).migrate()

        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || cause_reference || ':'
                || retired_profile_reference || ':' || retired_generation || ':' || erased_binding_revision
            from ums_profile_erasure_obligations where retired_profile_reference = ?""",
            String::class.java, retiredProfile))
            .isEqualTo("APP_DELETION:$erasedRequest:$retiredProfile:2:3")
        assertThat(jdbc.queryForObject("""select obligation_kind || ':' || (cause_reference is null) || ':'
                || retired_generation || ':' || (obligation_at = ?)
            from ums_profile_erasure_obligations where retired_profile_reference = ?""",
            String::class.java, java.sql.Timestamp.from(acceptedAt), expiredProfile))
            .isEqualTo("CLOSURE_EXPIRY:true:4:true")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
            Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("""select lifecycle || ':' || (profile_reference is null)
            from ums_profile_bindings where subject_reference = ?""", String::class.java, erasedSubject))
            .isEqualTo("ERASED:true")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_association_state",
            Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("""select status || ':' || generation || ':' ||
                (deadline = accepted_at + interval '720 hours')
            from ums_profile_deletion_requests where request_reference = ?""", String::class.java, pendingRequest))
            .isEqualTo("PENDING:2:true")
        assertThat(jdbc.queryForObject("""select count(*) from ums_profile_deletion_causes
            where request_reference = ? and deadline = ?""", Int::class.java, pendingRequest,
            java.sql.Timestamp.from(pendingAccepted.plusSeconds(720 * 3600)))).isEqualTo(1)
        assertThat(jdbc.queryForObject("select to_regclass('ums_profile_erasure_fences')::text",
            String::class.java)).isNull()
        assertThat(flyway(jdbc).info().current()?.version?.version).isEqualTo(LATEST)

        // The upgraded database is usable by the application, not just valid on paper.
        com.vik.ums.profiles.ProfileProvisioningService(jdbc,
            org.springframework.transaction.support.TransactionTemplate(
                org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!)),
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(), "test")
            .provision(com.vik.ums.profiles.TrustedOnboardingFact(
                com.vik.ums.profiles.ProfileScope(issuer, app, UUID.randomUUID()),
                "iam", UUID.randomUUID(), "entry", 1, "upgrade"))
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings where lifecycle = 'ACTIVE'",
            Int::class.java)).isEqualTo(1)
    }

    @Test fun `an interrupted migration is detectable and resumable`(@TempDir directory: Path) {
        val jdbc = database("upgrade_interrupted")
        val migrations = directory.resolve("migrations")
        Files.createDirectories(migrations)
        Files.writeString(migrations.resolve("V1__first.sql"), "create table upgrade_probe (id int primary key);")
        Files.writeString(migrations.resolve("V2__broken.sql"), "create table upgrade_probe (id int);")
        val flyway = Flyway.configure().dataSource(jdbc.dataSource!!)
            .locations("filesystem:$migrations").load()

        assertThatThrownBy { flyway.migrate() }.isInstanceOf(org.flywaydb.core.api.FlywayException::class.java)

        assertThat(flyway.info().current()?.version?.version).isEqualTo("1")
        assertThat(flyway.info().pending().map { it.version.version }).containsExactly("2")
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'upgrade_probe'",
            Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("""select count(*) from flyway_schema_history
            where version = '2' and success""", Int::class.java)).isZero()

        Files.writeString(migrations.resolve("V2__broken.sql"), "create table upgrade_second (id int primary key);")
        val resumed = flyway.migrate()

        assertThat(resumed.success).isTrue()
        assertThat(flyway.info().current()?.version?.version).isEqualTo("2")
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'upgrade_second'",
            Int::class.java)).isEqualTo(1)
    }
}
