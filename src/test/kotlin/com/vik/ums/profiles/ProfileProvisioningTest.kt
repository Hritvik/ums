package com.vik.ums.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Testcontainers
class ProfileProvisioningTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.6-alpine")
    }
    private lateinit var jdbc: JdbcTemplate
    private lateinit var registry: ProfilePolicyRegistry
    private lateinit var service: ProfileProvisioningService
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private val scope = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
    private fun policy(required: Set<ProfileField> = emptySet()) = AppProfilePolicy(
        app, true, 1, required, ProfileField.entries.toSet(), "fixture-1", "fixture-1",
    )
    private fun fact(revision: Long = 1, event: UUID = UUID.randomUUID(), target: ProfileScope = scope) =
        TrustedOnboardingFact(target, "iam-test", event, "onboarding-${target.subjectReference}", revision, "test-correlation")

    @BeforeEach
    fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings, ums_iam_app_mappings, ums_iam_source_bindings, ums_event_inbox, ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        registry = ProfilePolicyRegistry(jdbc, tx, mapper)
        service = ProfileProvisioningService(jdbc, tx, mapper, "test")
    }

    @Test
    fun `eligible entry atomically creates ready binding and canonical event`() {
        registry.register(policy())
        val result = service.provision(fact())
        assertThat(result.outcome).isEqualTo(ProvisioningOutcome.PROVISIONED)
        val status = service.status(app, scope)
        assertThat(status.state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        assertThat(status.state?.bindingRevision).isEqualTo("1")
        assertThat(status.state?.scope).isEqualTo(scope)
        assertThat(status.unmetFields).isEmpty()
        assertThat(count("ums_event_inbox")).isEqualTo(1)
        assertThat(count("ums_input_checkpoints")).isEqualTo(1)
        assertThat(jdbc.queryForObject("select stream_id from ums_event_outbox", String::class.java))
            .isEqualTo("${scope.issuerEnvironmentRef}:${scope.appReference}:${scope.subjectReference}")
        val payload = jdbc.queryForObject("select payload::text from ums_event_outbox", String::class.java)!!
        val event = mapper.readTree(payload)
        assertThat(event["type"].asText()).isEqualTo("ums.profile.state-changed.v1")
        assertThat(event["state"]["profileReference"].asText()).isEqualTo(status.state?.profileReference.toString())
        assertThat(event["state"]["deletionCauses"].size()).isZero()
        val path = Path.of("build/generated-contract-fixtures/provisioned.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, payload)
    }

    @Test
    fun `all scope dimensions isolate profiles and environment is bounded`() {
        registry.register(policy())
        val otherApp = app.copy(appReference = UUID.randomUUID())
        val otherIssuer = app.copy(issuerEnvironmentRef = UUID.randomUUID())
        registry.register(policy().copy(app = otherApp))
        registry.register(policy().copy(app = otherIssuer))
        val scopes = listOf(scope, scope.copy(appReference = otherApp.appReference),
            scope.copy(issuerEnvironmentRef = otherIssuer.issuerEnvironmentRef),
            scope.copy(subjectReference = UUID.randomUUID()))
        val refs = scopes.map { service.provision(fact(target = it)).state?.profileReference }
        assertThat(refs).doesNotHaveDuplicates().doesNotContainNull()
        assertThatThrownBy { service.status(otherIssuer, scope) }.isInstanceOf(IllegalArgumentException::class.java)
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        assertThatThrownBy {
            ProfileProvisioningService(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)), mapper, "bad env")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `strict policy needs deliberate fields and status never provisions`() {
        registry.register(policy(setOf(ProfileField.DISPLAY_NAME)))
        assertThat(service.status(app, scope).state).isNull()
        assertThat(count("ums_profile_bindings")).isZero()
        service.provision(fact())
        val status = service.status(app, scope)
        assertThat(status.state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(status.unmetFields).containsExactly(ProfileField.DISPLAY_NAME)
        assertThat(status.nextActions).containsExactly("COMPLETE_PROFILE")
    }

    @Test
    fun `unregistered disabled and wrong app have no provisioning effect`() {
        assertThat(service.provision(fact()).outcome).isEqualTo(ProvisioningOutcome.NOT_ADOPTED)
        registry.register(policy().copy(adopted = false))
        assertThat(service.provision(fact()).outcome).isEqualTo(ProvisioningOutcome.NOT_ADOPTED)
        assertThatThrownBy { service.status(AppScope(UUID.randomUUID(), UUID.randomUUID()), scope) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(count("ums_profile_bindings")).isZero()
    }

    @Test
    fun `identical retries and same revision new events never create new profile`() {
        registry.register(policy())
        val event = fact()
        val first = service.provision(event)
        assertThat(service.provision(event).outcome).isEqualTo(ProvisioningOutcome.DUPLICATE)
        assertThat(service.provision(fact()).outcome).isEqualTo(ProvisioningOutcome.STALE)
        assertThat(service.status(app, scope).state?.profileReference).isEqualTo(first.state?.profileReference)
        assertThat(count("ums_event_outbox")).isEqualTo(1)
        assertThatThrownBy { service.provision(event.copy(scope = scope.copy(subjectReference = UUID.randomUUID()))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `acknowledged stale event cannot be reused with different revision`() {
        registry.register(policy())
        service.provision(fact())
        val stale = fact()
        assertThat(service.provision(stale).outcome).isEqualTo(ProvisioningOutcome.STALE)
        assertThatThrownBy { service.provision(stale.copy(sourceRevision = 2)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(jdbc.queryForObject("select source_revision from ums_input_checkpoints", Long::class.java))
            .isEqualTo(1)
    }

    @Test
    fun `gap pauses provisioning and older events cannot regress checkpoint`() {
        registry.register(policy())
        assertThat(service.provision(fact(2)).outcome).isEqualTo(ProvisioningOutcome.RECONCILIATION_REQUIRED)
        assertThat(count("ums_profile_bindings")).isZero()
        service.provision(fact())
        service.provision(fact(2))
        assertThat(service.provision(fact()).outcome).isEqualTo(ProvisioningOutcome.STALE)
        assertThat(jdbc.queryForObject("select source_revision from ums_input_checkpoints", Long::class.java)).isEqualTo(2)
        assertThat(count("ums_event_outbox")).isEqualTo(1)
    }

    @Test
    fun `policy registration permits exact retry and refuses changes until reevaluation exists`() {
        registry.register(policy())
        registry.register(policy())
        assertThatThrownBy { registry.register(policy(setOf(ProfileField.DISPLAY_NAME))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { registry.register(policy().copy(policyRevision = 2)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `concurrent first entry produces exactly one binding and event`() {
        registry.register(policy())
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..8).map { Callable { service.provision(fact()) } }).map { it.get() }
            assertThat(results.count { it.outcome == ProvisioningOutcome.PROVISIONED }).isEqualTo(1)
            assertThat(count("ums_profile_bindings")).isEqualTo(1)
            assertThat(count("ums_event_outbox")).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `outbox failure rolls back inbox checkpoint and binding`() {
        registry.register(policy())
        jdbc.execute("alter table ums_event_outbox add constraint test_outbox_failure check (event_type <> 'ums.profile.state-changed.v1')")
        try {
            assertThatThrownBy { service.provision(fact()) }.isInstanceOf(RuntimeException::class.java)
            listOf("ums_event_inbox", "ums_input_checkpoints", "ums_profile_bindings").forEach {
                assertThat(count(it)).describedAs(it).isZero()
            }
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint test_outbox_failure") }
    }

    @Test
    fun `onboarding never reactivates restricted lifecycle`() {
        registry.register(policy())
        service.provision(fact())
        listOf("CLOSED", "DELETION_PENDING", "ERASED").forEach { lifecycle ->
            if (lifecycle == "ERASED") jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED',
                readiness = 'PROFILE_REQUIRED', profile_reference = null, generation = null, profile_revision = null""")
            else jdbc.update("update ums_profile_bindings set lifecycle = ?, readiness = 'PROFILE_REQUIRED'", lifecycle)
            assertThat(service.provision(fact(2)).outcome).isEqualTo(ProvisioningOutcome.RESTRICTED)
            assertThat(jdbc.queryForObject("select lifecycle from ums_profile_bindings", String::class.java)).isEqualTo(lifecycle)
        }
        assertThat(count("ums_event_outbox")).isEqualTo(1)
    }

    private fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!
}
