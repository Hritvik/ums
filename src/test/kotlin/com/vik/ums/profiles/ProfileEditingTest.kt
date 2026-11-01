package com.vik.ums.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Testcontainers
class ProfileEditingTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private val authority = TrustedHumanAuthority(scope, UUID.randomUUID(), setOf(HumanAction.READ, HumanAction.EDIT))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var editing: ProfileEditingService
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var registry: ProfilePolicyRegistry
    private val catalogs = ProfileCatalogs("locale-1", setOf("en", "en-IN"), "tz-1", setOf("UTC", "Asia/Kolkata"))

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        registry = ProfilePolicyRegistry(jdbc, tx, mapper)
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test")
        editing = ProfileEditingService(jdbc, tx, mapper, "test", catalogs, HmacPatchFingerprint(ByteArray(32) { 7 }))
        registry.register(AppProfilePolicy(app, true, 1, setOf(ProfileField.DISPLAY_NAME),
            ProfileField.entries.toSet(), "locale-1", "tz-1"))
        provisioning.provision(TrustedOnboardingFact(scope, "iam-test", UUID.randomUUID(), "entry", 1, "edit-test"))
    }

    @Test fun `explicit edits complete readiness and emit private canonical event`() {
        val initial = editing.display(authority)
        assertThat(initial.displayNameSource).isEqualTo("GENERATED_FALLBACK")
        assertThat(initial.fields).doesNotContainKey("displayName")
        val result = editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "  A\u0301da  ",
            ProfileField.DISPLAY_LOCALE to "en-IN", ProfileField.DISPLAY_TIMEZONE to "Asia/Kolkata")))
        assertThat(result.appliedRevision).isEqualTo("2")
        val display = editing.display(authority)
        assertThat(display.fields["displayName"]).isEqualTo("Áda")
        assertThat(display.displayNameSource).isEqualTo("EXPLICIT")
        val status = provisioning.status(app, scope)
        assertThat(status.state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        assertThat(status.unmetFields).isEmpty()
        val payload = jdbc.queryForObject("select payload::text from ums_event_outbox where stream_revision=2", String::class.java)!!
        assertThat(payload).doesNotContain("Áda", "Asia/Kolkata", "en-IN", "displayName")
        val projection = TrustedProjectionAuthority(app, "member-display", setOf(result.profileReference), ProfileField.entries.toSet())
        val batch = editing.displayBatch(projection, listOf(result.profileReference))
        write("edited-event", payload)
        write("status-after-edit", mapper.writeValueAsString(status))
        write("display-after-edit", mapper.writeValueAsString(display))
        write("display-batch", mapper.writeValueAsString(batch))
    }

    @Test fun `retry returns safe original revision while changed key input and stale versions fail`() {
        val cmd = command(mapOf(ProfileField.DISPLAY_NAME to "Private Name"))
        val result = editing.patch(authority, cmd)
        assertThat(editing.patch(authority, cmd)).isEqualTo(result)
        assertThatThrownBy { editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Other"), key = cmd.idempotencyKey)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Other"))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("select row_to_json(r)::text from ums_profile_patch_receipts r", String::class.java))
            .doesNotContain("Private Name", "displayName")
        jdbc.update("update ums_profile_bindings set lifecycle='CLOSED', readiness='PROFILE_REQUIRED'")
        assertThatThrownBy { editing.patch(authority, cmd) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { editing.display(authority) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `no-op clear records receipt without revision or event and explicit clear loses readiness`() {
        val noop = command(mapOf(ProfileField.DISPLAY_NAME to null, ProfileField.AVATAR_REFERENCE to null))
        assertThat(editing.patch(authority, noop).appliedRevision).isEqualTo("1")
        assertThat(editing.patch(authority, noop).appliedRevision).isEqualTo("1")
        assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isEqualTo(1)
        editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Name")))
        editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to null), 2))
        assertThat(provisioning.status(app, scope).state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(editing.display(authority).fields).doesNotContainKey("displayName")
    }

    @Test fun `invalid values empty patch and avatar attachment cannot change state`() {
        val invalid = listOf(
            mapOf(ProfileField.DISPLAY_NAME to " "), mapOf(ProfileField.DISPLAY_NAME to "bad\nname"),
            mapOf(ProfileField.DISPLAY_NAME to "bad\u202ename"), mapOf(ProfileField.DISPLAY_NAME to "\uD800"),
            mapOf(ProfileField.DISPLAY_NAME to "x".repeat(81)), mapOf(ProfileField.DISPLAY_LOCALE to "zz"),
            mapOf(ProfileField.DISPLAY_TIMEZONE to "Mars/Test"), mapOf(ProfileField.AVATAR_REFERENCE to "asset_ref"),
            emptyMap(),
        )
        invalid.forEach { values ->
            assertThatThrownBy { editing.patch(authority, command(values)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_patch_receipts", Long::class.java)).isZero()
    }

    @Test fun `current action and field permissions and catalog versions precede retry`() {
        val cmd = command(mapOf(ProfileField.DISPLAY_NAME to "Name"))
        editing.patch(authority, cmd)
        assertThatThrownBy { editing.patch(authority.copy(actions = setOf(HumanAction.READ)), cmd) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { editing.display(authority.copy(actions = setOf(HumanAction.EDIT))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy, '{humanEditableFields}', '[]'::jsonb)")
        assertThatThrownBy { editing.patch(authority, cmd) }.isInstanceOf(IllegalArgumentException::class.java)
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy, '{supportedLocaleDataVersion}', '\"locale-2\"'::jsonb)")
        assertThatThrownBy { editing.patch(authority, cmd) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `projection returns uniform unavailable and only approved fields`() {
        val ref = editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Name"))).profileReference
        val unknown = UUID.randomUUID()
        val projection = TrustedProjectionAuthority(app, "members", setOf(ref), setOf(ProfileField.DISPLAY_LOCALE))
        val result = editing.displayBatch(projection, listOf(ref, unknown))
        assertThat(result.results[0].display?.fields).isEmpty()
        assertThat(result.results[0].display?.displayNameSource).isEqualTo("ABSENT")
        assertThat(result.results[1].status).isEqualTo("UNAVAILABLE")
        assertThat(editing.displayBatch(projection.copy(permittedTargets = emptySet()), listOf(ref)).results[0].status)
            .isEqualTo("UNAVAILABLE")
        assertThat(editing.displayBatch(projection.copy(app = app.copy(appReference = UUID.randomUUID())), listOf(ref)).results[0].status)
            .isEqualTo("UNAVAILABLE")
        jdbc.update("update ums_profile_bindings set lifecycle='CLOSED', readiness='PROFILE_REQUIRED'")
        assertThat(editing.displayBatch(projection, listOf(ref)).results[0].status).isEqualTo("UNAVAILABLE")
        listOf(emptyList(), listOf(ref, ref), List(101) { UUID.randomUUID() }).forEach { refs ->
            assertThatThrownBy { editing.displayBatch(projection, refs) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { editing.displayBatch(projection.copy(purpose = ""), listOf(ref)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `late outbox failure rolls back field update and receipt`() {
        jdbc.execute("alter table ums_event_outbox add constraint test_edit_failure check (stream_revision <> 2)")
        try {
            assertThatThrownBy { editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Name"))) }
                .isInstanceOf(RuntimeException::class.java)
            assertThat(editing.display(authority).profileRevision).isEqualTo("1")
            assertThat(editing.display(authority).fields).doesNotContainKey("displayName")
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_patch_receipts", Long::class.java)).isZero()
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint test_edit_failure") }
    }

    @Test fun `concurrent expected versions permit one edit and one event`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll(listOf("One", "Two").map { name ->
                Callable { runCatching { editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to name))) } }
            }).map { it.get() }
            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.count { it.exceptionOrNull() is IllegalStateException }).isEqualTo(1)
            assertThat(editing.display(authority).profileRevision).isEqualTo("2")
            assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isEqualTo(2)
        } finally { pool.shutdownNow() }
    }

    @Test fun `retry ignores transport correlation and snapshots protect caller-owned collections`() {
        val changes = mutableMapOf<ProfileField, String?>(ProfileField.DISPLAY_NAME to "Original")
        val actions = mutableSetOf(HumanAction.READ, HumanAction.EDIT)
        val snapAuthority = TrustedHumanAuthority(scope, UUID.randomUUID(), actions)
        val cmd = command(changes)
        changes[ProfileField.DISPLAY_NAME] = "Changed"
        actions.clear()
        val first = editing.patch(snapAuthority, cmd)
        val retry = ProfilePatchCommand(cmd.idempotencyKey, cmd.expectedVersion, cmd.changes, "new-correlation")
        assertThat(editing.patch(snapAuthority, retry)).isEqualTo(first)
        assertThat(editing.display(snapAuthority).fields["displayName"]).isEqualTo("Original")
        assertThatThrownBy { editing.display(authority.copy(scope = scope.copy(subjectReference = UUID.randomUUID()))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { editing.patch(authority, command(mapOf(ProfileField.DISPLAY_NAME to "Other"), version = 0)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun command(changes: Map<ProfileField, String?>, version: Long = 1, key: UUID = UUID.randomUUID()) =
        ProfilePatchCommand(key, version, changes, "edit-test")
    private fun write(name: String, json: String) {
        val path = Path.of("build/generated-contract-fixtures/$name.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, json)
    }
}
