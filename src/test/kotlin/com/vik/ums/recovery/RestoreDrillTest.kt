package com.vik.ums.recovery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.ProfileFinalizationService
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.privacy.TrustedPrivacyAuthority
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfilePolicyRegistry
import com.vik.ums.profiles.ProfileProvisioningService
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.TrustedOnboardingFact
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The recorded drill: take a backup, erase a profile, restore the backup into a separate
 * database, and prove reconciliation puts the erasure back before the profile can serve.
 */
@Testcontainers
class RestoreDrillTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private val issuer = UUID.randomUUID()
    private val app = UUID.randomUUID()
    private val scope = ProfileScope(issuer, app, UUID.randomUUID())

    @TempDir lateinit var directory: Path

    @Test fun `a backup taken before an erasure restores into an erased profile`() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        ProfilePolicyRegistry(jdbc, transactions, mapper).register(
            AppProfilePolicy(AppScope(issuer, app), true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        ProfileProvisioningService(jdbc, transactions, mapper, "test", clock)
            .provision(TrustedOnboardingFact(scope, "iam", UUID.randomUUID(), "entry", 1, "drill"))
        jdbc.update("update ums_profile_bindings set display_name = 'Private Name', display_locale = 'en'")
        val backupAgeAtRestore = Instant.now()
        dump("ums_drill_backup.sql")
        val backupTakenAt = Instant.now()

        val deletion = ProfileDeletionService(jdbc, transactions, mapper, "test", clock, Duration.ofMinutes(5))
        val accepted = deletion.request(TrustedPrivacyAuthority(scope, UUID.randomUUID(),
            PrivacyPurpose.REQUEST_DELETION, clock.instant(), true, clock.instant().plus(Duration.ofMinutes(5))),
            DeletionRequestCommand(UUID.randomUUID(), 1, "drill"))
        clock.advance(Duration.ofDays(30))
        ProfileFinalizationService(jdbc, transactions, mapper, "test", clock)
            .finalize(TrustedMaintenanceAuthority(AppScope(issuer, app), UUID.randomUUID(),
                MaintenancePurpose.FINALIZE_APP_DELETION), scope, accepted.operationReference, "drill")
        val journal = ErasureJournal(directory.resolve("erasure-journal.jsonl"))
        ErasureJournalFlusher(ErasureFeed(jdbc), journal).flush()
        assertThat(journal.entries()).hasSize(1)

        val restoreStarted = Instant.now()
        shell("psql -U ${postgres.username} -d postgres -c 'drop database if exists ums_drill_restored'")
        shell("psql -U ${postgres.username} -d postgres -c 'create database ums_drill_restored'")
        shell("psql -U ${postgres.username} -d ums_drill_restored -f /tmp/ums_drill_backup.sql")
        val restored = DriverManagerDataSource(
            "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/ums_drill_restored",
            postgres.username, postgres.password)
        val restoredJdbc = JdbcTemplate(restored)
        val restoredTransactions = TransactionTemplate(DataSourceTransactionManager(restored))

        // The restored copy carries the pre-deletion row and no journal feed: the evidence must
        // come from outside the backup.
        assertThat(restoredJdbc.queryForObject("""select lifecycle || ':' || display_name
            from ums_profile_bindings where subject_reference = ?""", String::class.java, scope.subjectReference))
            .isEqualTo("ACTIVE:Private Name")
        assertThat(restoredJdbc.queryForObject("select count(*) from ums_erasure_journal_feed", Int::class.java)).isZero()
        val fencesBefore = restoredJdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
            Int::class.java)!!

        val result = RestoreReconciler(restoredJdbc, restoredTransactions, mapper, "test", journal, Clock.systemUTC()).reconcile()
        val elapsed = Duration.between(restoreStarted, Instant.now())

        assertThat(result.erased).isEqualTo(1)
        assertThat(restoredJdbc.queryForObject("""select lifecycle || ':' || (display_name is null) || ':'
                || (profile_reference is null) from ums_profile_bindings where subject_reference = ?""",
            String::class.java, scope.subjectReference)).isEqualTo("ERASED:true:true")
        assertThat(restoredJdbc.queryForObject("""select display_name from ums_profile_bindings
            where subject_reference = ?""", String::class.java, scope.subjectReference)).isNull()

        val fencesAfter = restoredJdbc.queryForObject("select count(*) from ums_profile_erasure_obligations",
            Int::class.java)!!
        val migration = restoredJdbc.queryForObject("select max(version::int) from flyway_schema_history",
            String::class.java)!!
        val checkpoints = restoredJdbc.queryForObject("select count(*) from ums_input_checkpoints", Int::class.java)!!
        val backupAge = Duration.between(backupTakenAt, restoreStarted)
        val record = mapOf(
            "backupAgeMillis" to backupAge.toMillis(),
            "lostChanges" to 1,
            "fencesBefore" to fencesBefore,
            "fencesAfter" to fencesAfter,
            "migrationVersion" to migration,
            "checkpoints" to checkpoints,
            "elapsedMillis" to elapsed.toMillis(),
            "journalEntries" to journal.entries().size,
        )
        println("RESTORE-DRILL $record")
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"))
        java.nio.file.Files.writeString(java.nio.file.Path.of("build", "restore-drill.json"),
            mapper.writeValueAsString(record))

        assertThat(fencesAfter).isEqualTo(fencesBefore + 1)
        assertThat(migration).isEqualTo("23")
        assertThat(checkpoints).isEqualTo(1)
        assertThat(backupAge).isLessThan(Duration.ofMinutes(5))
        assertThat(elapsed).isLessThan(Duration.ofMinutes(2))
    }

    private fun dump(file: String) {
        shell("pg_dump -U ${postgres.username} -d ${postgres.databaseName} --inserts -f /tmp/$file")
    }

    private fun shell(command: String) {
        val result = postgres.execInContainer("sh", "-c",
            "PGPASSWORD='${postgres.password}' " + command)
        check(result.exitCode == 0) { "drill command failed: ${result.stderr}" }
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
