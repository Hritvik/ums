package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
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
        const val LATEST = "23"
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine")
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

    @Test fun `an upgrade keeps an erased tombstone, its fence, and a pending request`() {
        val jdbc = database("upgrade_data")
        Flyway.configure().dataSource(jdbc.dataSource!!).target("21").load().migrate()
        val issuer = UUID.randomUUID()
        val app = UUID.randomUUID()
        val erasedSubject = UUID.randomUUID()
        val pendingSubject = UUID.randomUUID()
        val retiredProfile = UUID.randomUUID()
        jdbc.update("insert into ums_profile_policies (issuer_environment_ref, app_reference, policy) values (?, ?, '{}')",
            issuer, app)
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, binding_revision, policy_revision,
             lifecycle, readiness)
            values (?, ?, ?, 3, 1, 'ERASED', 'PROFILE_REQUIRED')""", issuer, app, erasedSubject)
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             retired_profile_reference, retired_generation, obligation_at, erased_binding_revision, erased_at)
            values (?, ?, ?, ?, 'APP_DELETION', ?, 2, now(), 3, now())""",
            UUID.randomUUID(), issuer, app, erasedSubject, retiredProfile)
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, profile_reference, binding_revision,
             policy_revision, generation, profile_revision, lifecycle, readiness)
            values (?, ?, ?, ?, 2, 1, 2, 2, 'DELETION_PENDING', 'PROFILE_REQUIRED')""",
            issuer, app, pendingSubject, UUID.randomUUID())
        val request = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference, profile_reference,
             generation, operation_revision, status, accepted_at, deadline)
            select ?, issuer_environment_ref, app_reference, subject_reference, profile_reference,
                generation, 1, 'PENDING', now(), now() + interval '720 hours'
            from ums_profile_bindings where subject_reference = ?""", request, pendingSubject)

        flyway(jdbc).migrate()

        assertThat(jdbc.queryForObject("""select lifecycle || ':' || (profile_reference is null)
            from ums_profile_bindings where subject_reference = ?""", String::class.java, erasedSubject))
            .isEqualTo("ERASED:true")
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
            Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("""select status || ':' || (deadline > now())
            from ums_profile_deletion_requests where request_reference = ?""", String::class.java, request))
            .isEqualTo("PENDING:true")
        assertThat(flyway(jdbc).info().current()?.version?.version).isEqualTo(LATEST)
    }

    @Test fun `an interrupted migration is detectable and resumable`(@TempDir directory: Path) {
        val jdbc = database("upgrade_interrupted")
        val migrations = directory.resolve("migrations")
        Files.createDirectories(migrations)
        Files.writeString(migrations.resolve("V1__first.sql"), "create table upgrade_probe (id int primary key);")
        Files.writeString(migrations.resolve("V2__broken.sql"), "create table upgrade_probe (id int);")
        val flyway = Flyway.configure().dataSource(jdbc.dataSource!!)
            .locations("filesystem:$migrations").load()

        assertThatThrownBy { flyway.migrate() }.isInstanceOf(Exception::class.java)

        assertThat(flyway.info().current()?.version?.version).isEqualTo("1")
        assertThat(flyway.info().pending().map { it.version.version }).containsExactly("2")
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'upgrade_probe'",
            Int::class.java)).isEqualTo(1)

        Files.writeString(migrations.resolve("V2__broken.sql"), "create table upgrade_second (id int primary key);")
        val resumed = flyway.migrate()

        assertThat(resumed.success).isTrue()
        assertThat(flyway.info().current()?.version?.version).isEqualTo("2")
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'upgrade_second'",
            Int::class.java)).isEqualTo(1)
    }
}
