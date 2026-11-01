package com.vik.ums.journey

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.iam.IamOnboardingAdapter
import com.vik.ums.iam.TrustedIamSource
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
import java.util.UUID

/** Local integration with a simulated Dutch consumer; never real product E2E/provider acceptance. */
@Testcontainers
class LocalConsumerJourneyTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val app = AppScope(UUID.randomUUID(), UUID.randomUUID())
    private lateinit var jdbc: JdbcTemplate
    private lateinit var adapter: IamOnboardingAdapter
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var editing: ProfileEditingService
    private lateinit var registry: ProfilePolicyRegistry

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_profile_erasure_obligations, ums_profile_privacy_audit, ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes, ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox, ums_event_outbox,
            ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        registry = ProfilePolicyRegistry(jdbc, tx, mapper)
        registry.register(AppProfilePolicy(app, true, 1,
            setOf(ProfileField.DISPLAY_NAME), ProfileField.entries.toSet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test")
        editing = ProfileEditingService(jdbc, tx, mapper, "test",
            ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")), HmacPatchFingerprint(ByteArray(32) { 9 }))
        adapter = IamOnboardingAdapter(jdbc, tx, provisioning,
            TrustedIamSource(app.issuerEnvironmentRef, "test"), mapOf("dutch-development" to app))
    }

    @Test fun `strict profile journey keeps Dutch member identity and Ledger authority product-owned`() {
        val consumer = SimulatedDutchConsumer(app, "test")
        assertThat(consumer.state()).isEqualTo("WAIT")
        val provisioned = adapter.consume(input(), "dutch-development")
        val binding = requireNotNull(provisioned.state)
        val scope = binding.scope
        val profileReference = requireNotNull(binding.profileReference)
        val authority = TrustedHumanAuthority(scope, UUID.randomUUID(), HumanAction.entries.toSet())
        assertThat(provisioning.status(app, scope).state?.readiness).isEqualTo(Readiness.PROFILE_REQUIRED)
        assertThat(consumer.deliver(outbox(1))).isEqualTo("APPLIED")
        assertThat(consumer.state()).isEqualTo("COMPLETE_PROFILE")
        val member = consumer.memberReference()
        assertThat(member).isNotNull().isNotEqualTo(profileReference)
        assertThat(consumer.canUseLedger()).isFalse()
        editing.patch(authority, ProfilePatchCommand(UUID.randomUUID(), 1, mapOf(ProfileField.DISPLAY_NAME to "Ada"), "journey"))
        val readyEvent = outbox(2)
        assertThat(consumer.deliver(readyEvent)).isEqualTo("APPLIED")
        assertThat(consumer.deliver(readyEvent)).isEqualTo("DUPLICATE")
        assertThat(adapter.consume(input(), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.DUPLICATE)
        assertThat(consumer.memberReference()).isEqualTo(member)
        assertThat(consumer.state()).isEqualTo("READY")
        assertThat(consumer.canUseLedger()).isFalse()
        consumer.grantLedgerPermission(member!!)
        assertThat(consumer.canUseLedger()).isTrue()
        val status = provisioning.status(app, scope)
        assertThat(status.state?.readiness).isEqualTo(Readiness.PROFILE_READY)
        assertThat(editing.display(authority).fields["displayName"]).isEqualTo("Ada")
        val projection = TrustedProjectionAuthority(app, "member-display", setOf(profileReference),
            setOf(ProfileField.DISPLAY_NAME))
        val projected = editing.displayBatch(projection, listOf(profileReference))
        assertThat(projected.results.single().display?.fields).containsOnlyKeys("displayName")
        assertThat(projected.results.single().display?.fields?.get("displayName")).isEqualTo("Ada")
        val foreignApp = app.copy(appReference = UUID.randomUUID())
        registry.register(AppProfilePolicy(foreignApp, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
        assertThat(editing.displayBatch(projection.copy(app = foreignApp),
            listOf(profileReference)).results.single().status).isEqualTo("UNAVAILABLE")
        write("journey-event", readyEvent)
        write("journey-status", mapper.writeValueAsString(status))
        write("journey-display", mapper.writeValueAsString(editing.display(authority)))
    }

    @Test fun `outbox gaps invalidate previously ready projection until missing delivery and explicit retry`() {
        val scope = adapter.consume(input(), "dutch-development").state!!.scope
        val authority = TrustedHumanAuthority(scope, UUID.randomUUID(), HumanAction.entries.toSet())
        val consumer = SimulatedDutchConsumer(app, "test")
        consumer.deliver(outbox(1))
        editing.patch(authority, patch(1, "Ada"))
        consumer.deliver(outbox(2))
        consumer.grantLedgerPermission(consumer.memberReference()!!)
        assertThat(consumer.canUseLedger()).isTrue()
        editing.patch(authority, patch(2, null))
        editing.patch(authority, patch(3, "New name"))
        assertThat(consumer.deliver(outbox(4))).isEqualTo("REPAIR_REQUIRED")
        assertThat(consumer.state()).isEqualTo("REPAIR_REQUIRED")
        assertThat(consumer.canUseLedger()).isFalse()
        assertThat(consumer.deliver(outbox(3))).isEqualTo("APPLIED")
        assertThat(consumer.state()).isEqualTo("REPAIR_REQUIRED")
        assertThat(consumer.canUseLedger()).isFalse()
        assertThat(consumer.deliver(outbox(4))).isEqualTo("APPLIED")
        assertThat(consumer.state()).isEqualTo("READY")
        assertThat(consumer.canUseLedger()).isTrue()
        assertThat(consumer.deliver(outbox(2))).isEqualTo("DUPLICATE")
        assertThat(consumer.state()).isEqualTo("READY")
    }

    @Test fun `wrong application and metadata collision negatives never fabricate Member authority`() {
        adapter.consume(input(), "dutch-development")
        val actual = outbox(1)
        val foreign = SimulatedDutchConsumer(app.copy(appReference = UUID.randomUUID()), "test")
        assertThat(foreign.deliver(actual)).isEqualTo("UNAVAILABLE")
        assertThat(foreign.memberReference()).isNull()
        assertThat(foreign.state()).isEqualTo("WAIT")
        val consumer = SimulatedDutchConsumer(app, "test")
        consumer.deliver(actual)
        // Explicit test-only corruption negatives. Normal journey deliveries use unchanged persisted JSON.
        val changedContent = mapper.readTree(actual).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (changedContent["state"] as com.fasterxml.jackson.databind.node.ObjectNode).put("readiness", "PROFILE_READY")
        assertThatThrownBy { consumer.deliver(mapper.writeValueAsString(changedContent)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        changedContent.put("eventId", UUID.randomUUID().toString())
        assertThatThrownBy { consumer.deliver(mapper.writeValueAsString(changedContent)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(consumer.state()).isEqualTo("COMPLETE_PROFILE")
        assertThat(consumer.canUseLedger()).isFalse()
    }

    @Test fun `no message remains waiting and failed UMS transaction safely retries original IAM event`() {
        val consumer = SimulatedDutchConsumer(app, "test")
        val absent = ProfileScope(app.issuerEnvironmentRef, app.appReference, UUID.randomUUID())
        assertThat(provisioning.status(app, absent).state).isNull()
        assertThat(consumer.state()).isEqualTo("WAIT")
        jdbc.execute("alter table ums_event_outbox add constraint journey_outbox_failure check (stream_revision <> 1)")
        try {
            assertThatThrownBy { adapter.consume(input(), "dutch-development") }.isInstanceOf(RuntimeException::class.java)
            assertThat(jdbc.queryForObject("select count(*) from ums_profile_bindings", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("select count(*) from ums_event_outbox", Long::class.java)).isZero()
            assertThat(consumer.state()).isEqualTo("WAIT")
        } finally { jdbc.execute("alter table ums_event_outbox drop constraint journey_outbox_failure") }
        assertThat(adapter.consume(input(), "dutch-development").outcome).isEqualTo(ProvisioningOutcome.PROVISIONED)
        assertThat(consumer.state()).isEqualTo("WAIT") // Committed outbox is not downstream delivery.
        assertThat(consumer.deliver(outbox(1))).isEqualTo("APPLIED")
        assertThat(consumer.state()).isEqualTo("COMPLETE_PROFILE")
    }

    @Test fun `first gap pins subject before another same-app subject can contaminate stream`() {
        val scopeA = adapter.consume(input(), "dutch-development").state!!.scope
        editing.patch(TrustedHumanAuthority(scopeA, UUID.randomUUID(), HumanAction.entries.toSet()), patch(1, "A"))
        val eventA2 = outbox(2)
        val scopeB = adapter.consume(input().replace("evt_0123456789abcdef", "evt_second0000000000")
            .replace("act_0123456789abcdef", "act_second0000000000")
            .replace("aps_0123456789abcdefghijkL", "aps_second0000000000000000"), "dutch-development").state!!.scope
        val eventB1 = jdbc.queryForObject("""select payload::text from ums_event_outbox
            where stream_id = ? and stream_revision = 1""", String::class.java,
            "${scopeB.issuerEnvironmentRef}:${scopeB.appReference}:${scopeB.subjectReference}")!!
        val consumer = SimulatedDutchConsumer(app, "test")
        assertThat(consumer.deliver(eventA2)).isEqualTo("REPAIR_REQUIRED")
        assertThat(consumer.memberReference()).isNull()
        assertThat(consumer.deliver(eventB1)).isEqualTo("UNAVAILABLE")
        assertThat(consumer.state()).isEqualTo("REPAIR_REQUIRED")
        assertThat(consumer.memberReference()).isNull()
    }

    private fun patch(version: Long, name: String?) = ProfilePatchCommand(
        UUID.randomUUID(), version, mapOf(ProfileField.DISPLAY_NAME to name), "journey")

    private fun outbox(revision: Long): String = jdbc.queryForObject(
        "select payload::text from ums_event_outbox where stream_revision = ?", String::class.java, revision)!!
    private fun write(name: String, payload: String) {
        val path = Path.of("build/generated-contract-fixtures/$name.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, payload)
    }
    private fun input(): String = """{"eventId":"evt_0123456789abcdef","eventType":"iam.app-subject.onboarding-ready.v1",
        "schemaVersion":1,"occurredAt":"2026-10-23T10:15:30Z","producer":"iam","environment":"test",
        "aggregate":{"type":"APP_ONBOARDING","id":"act_0123456789abcdef","version":1},
        "correlationId":"journey","causationId":null,"destination":{"appRegistrationId":"dutch-development"},
        "payload":{"appRegistrationId":"dutch-development","appSubject":"aps_0123456789abcdefghijkL",
        "activationId":"act_0123456789abcdef","activationState":"ACTIVE","activationVersion":1,
        "onboardingReason":"FIRST_ACTIVATION","onboardingVersion":1,"requiredProfileState":"PROFILE_REQUIRED"}}"""
}
