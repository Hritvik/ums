package com.vik.ums.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.privacy.ProfileDeletionService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.Duration
import java.util.UUID

@Testcontainers
class ProfileStatusHttpTest {
    companion object { @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine") }
    private val mapper = jacksonObjectMapper()
    private val issuerEnvironmentRef = UUID.randomUUID()
    private val appReference = UUID.randomUUID()
    private val subjectReference = UUID.randomUUID()
    private val registrationJson = """[{"appId":"dutch-development","clientId":"dutch-mobile",
        "audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true},
        {"appId":"other-app","clientId":"other-client","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]"""
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
        ProfilePolicyRegistry(jdbc, tx, mapper).register(
            AppProfilePolicy(app, true, 1, emptySet(), ProfileField.entries.toSet(), "l1", "t1"))
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
        val deletion = ProfileDeletionService(jdbc, tx, mapper, "test", java.time.Clock.systemUTC(), Duration.ofMinutes(5))
        mockMvc = MockMvcBuilders.standaloneSetup(
            ProfileStatusController(provisioning, scopeResolver, mapper),
            ProfileController(editing, scopeResolver, mapper),
            ProfilePatchController(editing, provisioning, scopeResolver, mapper),
            ProfilePrivacyController(deletion, scopeResolver, mapper, Duration.ofMinutes(5)),
        )
            .addInterceptors(CallerAuthorityInterceptor(resolver, mapper))
            .build()
    }

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(acr: String = "urn:distracted:aal2",
                             authenticatedAt: Instant? = Instant.now().minusSeconds(10),
                             appId: String = "dutch-development",
                             clientId: String = "dutch-mobile") {
        val jwt = Jwt.withTokenValue("t").header("alg", "ES256").header("typ", "at+jwt")
            .header("kid", "dutch-key-01")
            .subject("aps_0123456789abcdefghijkL").issuer("https://iam.example.test")
            .audience(listOf("https://ums.example.test")).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("jti", "jti_0123456789abcdef").claim("client_id", clientId)
            .claim("token_profile", "human_access").claim("token_version", 1)
            .claim("subject_kind", "app_subject").claim("scope", "profile:read")
            .claim("app_id", appId).claim("security_version", 1)
            .claim("acr", acr).claim("amr", listOf("passkey"))
            .apply { authenticatedAt?.let { claim("auth_time", it.epochSecond) } }
            .claim("cnf", mapOf("jkt" to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test fun `a mapped caller reads only its own status`() {
        authenticate()

        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.state.lifecycle").value("ACTIVE"))
            .andExpect(jsonPath("$.data.nextActions").isArray)
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

    private fun patchBody(name: String?, version: Long = 1): String =
        """{"expectedVersion":$version,"changes":{"displayName":${if (name == null) "null" else "\"$name\""}}}"""

    @Test fun `a targeted patch updates the display and replays safely`() {
        authenticate()
        val key = UUID.randomUUID()
        val body = patchBody("Ada")

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.profileRevision").value("2"))
            .andExpect(jsonPath("$.data.fields.displayName").value("Ada"))

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.profileRevision").value("2"))

        assertThat(jdbc.queryForObject("select profile_revision from ums_profile_bindings", Long::class.java))
            .isEqualTo(2)
    }

    @Test fun `version and idempotency conflicts are distinct`() {
        authenticate()
        val key = UUID.randomUUID()
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content(patchBody("Ada")))
            .andExpect(status().isOk)

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Grace", version = 9)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("version_conflict"))

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content(patchBody("Grace")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"))
    }

    @Test fun `malformed bodies are refused before any state change`() {
        authenticate()
        val cases = listOf(
            """{"expectedVersion":1,"changes":{"unknownField":"x"}}""",
            """{"expectedVersion":1,"expectedVersion":1,"changes":{"displayName":"Ada"}}""",
            """{"expectedVersion":1,"changes":{}}""",
            """{"expectedVersion":1,"changes":"not-an-object"}""",
            """{"expectedVersion":1,"changes":{"displayName":"Ada"}} trailing""",
        )
        cases.forEach { body ->
            mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", uuid()).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("malformed_input"))
        }
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON).content(patchBody("Ada")))
            .andExpect(status().isBadRequest)
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.TEXT_PLAIN)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada")))
            .andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("select profile_revision from ums_profile_bindings", Long::class.java)).isEqualTo(1)
    }

    @Test fun `an invalid field value is a semantic rejection`() {
        authenticate()

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada\u202Eevil")))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("invalid_field"))
    }

    @Test fun `a restricted or unmapped profile is unavailable to a patch`() {
        authenticate()
        jdbc.update("update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED'")
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))

        jdbc.update("update ums_profile_bindings set lifecycle = 'ACTIVE'")
        jdbc.update("delete from ums_iam_app_mappings")
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada")))
            .andExpect(status().isNotFound)
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    @Test fun `request status and cancellation form one privacy journey`() {
        authenticate()
        val key = UUID.randomUUID()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content("""{"expectedVersion":1}"""))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))

        val reference = jdbc.queryForObject("select request_reference from ums_profile_deletion_requests",
            UUID::class.java)!!
        val status = mockMvc.perform(get("/api/v1/profile/deletion-requests/$reference"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.cancellationEligible").value(true))
            .andReturn().response.contentAsString

        val bindingRevision = mapper.readTree(status)["data"]["bindingRevision"].asText()
        mockMvc.perform(post("/api/v1/profile/deletion-requests/$reference/cancellation")
            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
            .content("""{"expectedVersion":$bindingRevision}"""))
            .andExpect(status().isAccepted)

        assertThat(jdbc.queryForObject("select status from ums_profile_deletion_requests", String::class.java))
            .isEqualTo("CANCELLED")
    }

    @Test fun `privacy commands require recent strong authentication`() {
        authenticate(acr = "urn:distracted:aal1")
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("forbidden"))

        authenticate(authenticatedAt = Instant.now().minusSeconds(3_600))
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isForbidden)

        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_requests", Int::class.java)).isZero()
    }

    @Test fun `privacy commands are strict about body and identity`() {
        authenticate()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedVersion":1}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID()).content("""{"expectedVersion":9}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("version_conflict"))
        assertThat(jdbc.queryForObject("select count(*) from ums_profile_deletion_requests", Int::class.java)).isZero()
    }

    @Test fun `privacy status and cancellation refuse unknown references`() {
        authenticate()
        mockMvc.perform(get("/api/v1/profile/deletion-requests/not-a-uuid"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/profile/deletion-requests/" + uuid()))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/profile/deletion-requests/" + uuid() + "/cancellation")
            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", uuid())
            .content("""{"expectedVersion":1}"""))
            .andExpect(status().isNotFound)
    }

    @Test fun `privacy commands reject malformed transport and missing recent authentication`() {
        authenticate()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").content("""{"expectedVersion":1}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(post("/api/v1/profile/deletion-requests").header("Idempotency-Key", "not-a-uuid")
            .contentType(MediaType.APPLICATION_JSON).content("""{"expectedVersion":1}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("x".repeat(17 * 1024)))
            .andExpect(status().isBadRequest)

        authenticate(authenticatedAt = null)
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isForbidden)
    }

    @Test fun `an unmapped caller cannot start a privacy command`() {
        authenticate()
        jdbc.update("delete from ums_iam_app_mappings")

        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
    }

    @Test fun `reusing a privacy idempotency key with different content is a conflict`() {
        authenticate()
        val key = UUID.randomUUID()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content("""{"expectedVersion":1}"""))
            .andExpect(status().isAccepted)

        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key).content("""{"expectedVersion":2}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"))
    }

    @Test fun `a patch rejects oversized bodies and a malformed key before parsing`() {
        authenticate()
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "not-a-uuid").content(patchBody("Ada")))
            .andExpect(status().isBadRequest)
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("x".repeat(17 * 1024)))
            .andExpect(status().isBadRequest)
    }

    @Test fun `a token from another application cannot see this profile`() {
        val otherApp = UUID.randomUUID()
        jdbc.update("""insert into ums_iam_app_mappings (issuer_environment_ref, app_registration_id, app_reference)
            values (?, 'other-app', ?)""", issuerEnvironmentRef, otherApp)
        authenticate(appId = "other-app", clientId = "other-client")

        mockMvc.perform(get("/api/v1/profile"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
    }

    @Test fun `the published collection status read accepts an operation reference`() {
        authenticate()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isAccepted)
        val reference = jdbc.queryForObject("select request_reference from ums_profile_deletion_requests",
            UUID::class.java)!!

        mockMvc.perform(get("/api/v1/profile/deletion-requests").param("operationReference", reference.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
        mockMvc.perform(get("/api/v1/profile/deletion-requests"))
            .andExpect(status().isBadRequest)
    }

    @Test fun `a pending deletion blocks ordinary reads and edits`() {
        authenticate()
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isAccepted)

        mockMvc.perform(get("/api/v1/profile")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state.lifecycle").value("DELETION_PENDING"))
            .andExpect(jsonPath("$.data.nextActions[0]").value("CANCEL_DELETION"))
        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada")))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":2}"""))
            .andExpect(status().isConflict)
    }

    @Test fun `a withdrawn capability refuses a privacy command as unavailable`() {
        authenticate()
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy::jsonb, '{adopted}', 'false')")

        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
    }

    @Test fun `a withdrawn capability is unavailable rather than an invalid field`() {
        authenticate()
        jdbc.update("update ums_profile_policies set policy = jsonb_set(policy::jsonb, '{adopted}', 'false')")

        mockMvc.perform(patch("/api/v1/profile").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content(patchBody("Ada")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("unavailable"))
    }

    @Test fun `the strong-authentication window is exact`() {
        authenticate(authenticatedAt = Instant.now().minus(Duration.ofMinutes(5)))
        mockMvc.perform(post("/api/v1/profile/deletion-requests").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", uuid()).content("""{"expectedVersion":1}"""))
            .andExpect(status().isForbidden)
    }

    @Test fun `an unauthenticated request is refused before the handler`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("anonymous", null, emptyList())

        mockMvc.perform(get("/api/v1/profile/status"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("unauthenticated"))
    }
}
