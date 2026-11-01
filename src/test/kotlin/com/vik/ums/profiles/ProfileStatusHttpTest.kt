package com.vik.ums.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.iam.CallerRegistrations
import com.vik.ums.iam.IamCallerResolver
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@Testcontainers
class ProfileStatusHttpTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val issuerEnvironmentRef = UUID.randomUUID()
    private val appReference = UUID.randomUUID()
    private val subjectReference = UUID.randomUUID()
    private val registrationJson = """[{"appId":"dutch-development","clientId":"dutch-mobile",
        "audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]"""
    private lateinit var jdbc: JdbcTemplate
    private lateinit var provisioning: ProfileProvisioningService
    private lateinit var mockMvc: MockMvc

    @BeforeEach fun setup() {
        val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(ds).load().migrate()
        jdbc = JdbcTemplate(ds)
        jdbc.execute("""truncate ums_iam_ingestion_quarantine, ums_profile_notice_intents,
            ums_profile_erasure_obligations, ums_profile_lifecycle_evidence, ums_profile_privacy_audit,
            ums_profile_privacy_receipts, ums_profile_deletion_requests, ums_profile_deletion_causes,
            ums_iam_activation_owners, ums_iam_onboarding_receipts, ums_iam_subject_mappings,
            ums_iam_app_mappings, ums_iam_source_bindings, ums_profile_patch_receipts, ums_event_inbox,
            ums_event_outbox, ums_profile_bindings, ums_input_checkpoints, ums_profile_policies""")
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))
        val app = AppScope(issuerEnvironmentRef, appReference)
        ProfilePolicyRegistry(jdbc, tx, mapper).register(AppProfilePolicy(app, true, 1, emptySet(), emptySet(), "l1", "t1"))
        provisioning = ProfileProvisioningService(jdbc, tx, mapper, "test")
        provisioning.provision(TrustedOnboardingFact(ProfileScope(issuerEnvironmentRef, appReference, subjectReference),
            "iam", UUID.randomUUID(), "entry", 1, "http"))
        jdbc.update("insert into ums_iam_source_bindings (issuer_environment_ref, environment) values (?, 'test')",
            issuerEnvironmentRef)
        jdbc.update("""insert into ums_iam_app_mappings (issuer_environment_ref, app_registration_id, app_reference)
            values (?, 'dutch-development', ?)""", issuerEnvironmentRef, appReference)
        jdbc.update("""insert into ums_iam_subject_mappings
            (issuer_environment_ref, app_reference, app_subject, subject_reference, activation_id)
            values (?, ?, 'aps_0123456789abcdefghijkL', ?, 'act_0123456789abcdef')""",
            issuerEnvironmentRef, appReference, subjectReference)

        val registrations = CallerRegistrations.parse(registrationJson)
        val resolver = IamCallerResolver("https://iam.example.test", registrations)
        val editing = ProfileEditingService(jdbc, tx, mapper, "test",
            ProfileCatalogs("l1", setOf("en"), "t1", setOf("UTC")), HmacPatchFingerprint(ByteArray(32) { 7 }))
        val scopeResolver = ProfileScopeResolver(jdbc, issuerEnvironmentRef)
        mockMvc = MockMvcBuilders.standaloneSetup(
            ProfileStatusController(provisioning, scopeResolver, mapper),
            ProfileController(editing, scopeResolver, mapper),
        )
            .addInterceptors(CallerAuthorityInterceptor(resolver, mapper))
            .build()
    }

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val jwt = Jwt.withTokenValue("t").header("alg", "ES256").header("typ", "at+jwt")
            .header("kid", "dutch-key-01")
            .subject("aps_0123456789abcdefghijkL").issuer("https://iam.example.test")
            .audience(listOf("https://ums.example.test")).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("jti", "jti_0123456789abcdef").claim("client_id", "dutch-mobile")
            .claim("token_profile", "human_access").claim("token_version", 1)
            .claim("subject_kind", "app_subject").claim("scope", "profile:read")
            .claim("app_id", "dutch-development").claim("security_version", 1)
            .claim("acr", "urn:distracted:aal2").claim("amr", listOf("passkey"))
            .claim("cnf", mapOf("jkt" to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test fun `a mapped caller reads only its own status`() {
        authenticate()

        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state.lifecycle").value("ACTIVE"))
            .andExpect(jsonPath("$.nextActions").isArray)
    }

    @Test fun `an unmapped caller gets the same unavailable answer as a hidden target`() {
        authenticate()
        jdbc.update("delete from ums_iam_subject_mappings")

        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
            .andExpect(jsonPath("$.correlationId").exists())
    }

    @Test fun `the mapped caller reads only its own display envelope`() {
        authenticate()

        mockMvc.perform(get("/api/v1/profile"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.profileRevision").value("1"))
            .andExpect(jsonPath("$.data.displayNameSource").value("GENERATED_FALLBACK"))
            .andExpect(jsonPath("$.data.fields").isMap)
    }

    @Test fun `an unmapped caller cannot read a display`() {
        authenticate()
        jdbc.update("delete from ums_iam_app_mappings")

        mockMvc.perform(get("/api/v1/profile"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
    }

    @Test fun `an unauthenticated request is refused before the handler`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("anonymous", null, emptyList())

        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("unauthenticated"))
    }
}
