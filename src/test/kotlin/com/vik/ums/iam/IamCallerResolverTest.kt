package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipFile

/**
 * Conformance against the pinned IAM provider fixture, not hand-invented claims. The semantic
 * rules come from the provider's conformance requirements (issuer, app, client, audience,
 * scope subset, DPoP binding, and ordering).
 */
class IamCallerResolverTest {
    private val mapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val clock = FixedClock(now)
    private val fixture: Map<String, Any?> = ZipFile(File("contracts/dependencies/iam/iam-contracts-1.0.0.zip")).use { zip ->
        val entry = zip.getEntry("iam-contracts-1.0.0/fixtures/valid/token-claims.json")
        val parsed = mapper.readValue(zip.getInputStream(entry).readBytes().decodeToString(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        parsed["instance"] as Map<String, Any?>
    }
    private val registration = CallerRegistration(
        appId = "dutch-development",
        clientId = "dutch-mobile",
        audience = "https://api.dutch.invalid",
        allowedScopes = setOf("member:read"),
        requiresDpop = true,
    )
    private val resolver = IamCallerResolver("https://iam.distracted.invalid",
        CallerRegistrationLookup { app, client -> registration.takeIf { it.appId == app && it.clientId == client } },
        clock)

    private fun token(vararg overrides: Pair<String, Any?>): Jwt {
        val claims = fixture.toMutableMap().apply {
            put("iat", now)
            put("exp", now.plusSeconds(300))
            put("auth_time", now.minusSeconds(10))
            overrides.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
        }
        val builder = Jwt.withTokenValue("token").header("alg", "RS256")
        claims.forEach { (key, value) -> builder.claim(key, value) }
        return builder.build()
    }

    private fun denial(token: Jwt): CallerDenial =
        (runCatching { resolver.resolve(token) }.exceptionOrNull() as CallerDeniedException).reason

    @Test fun `the pinned provider fixture resolves to caller authority`() {
        val caller = resolver.resolve(token())

        assertThat(caller.appId).isEqualTo("dutch-development")
        assertThat(caller.clientId).isEqualTo("dutch-mobile")
        assertThat(caller.subjectReference).isEqualTo("aps_0123456789abcdefghijkL")
        assertThat(caller.securityVersion).isEqualTo(1)
        assertThat(caller.assurance).isEqualTo(Assurance.STRONG)
        assertThat(caller.authenticationMethods).containsExactly("passkey")
        assertThat(caller.scopes).containsExactly("member:read")
    }

    @Test fun `issuer audience and temporal rules are enforced`() {
        assertThat(denial(token("iss" to "https://other.invalid"))).isEqualTo(CallerDenial.ISSUER_MISMATCH)
        assertThat(denial(token("aud" to "https://api.other.invalid"))).isEqualTo(CallerDenial.AUDIENCE_MISMATCH)
        assertThat(denial(token("iat" to now.minusSeconds(600), "exp" to now.minusSeconds(60)))).isEqualTo(CallerDenial.EXPIRED)
        assertThat(denial(token("auth_time" to now.plusSeconds(60))))
            .isEqualTo(CallerDenial.TEMPORAL_INCONSISTENCY)
    }

    @Test fun `profile version and subject kind must match the published contract`() {
        assertThat(denial(token("token_profile" to "workload_access"))).isEqualTo(CallerDenial.PROFILE_MISMATCH)
        assertThat(denial(token("token_version" to 2))).isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(token("subject_kind" to "human"))).isEqualTo(CallerDenial.SUBJECT_KIND_MISMATCH)
    }

    @Test fun `malformed references and scopes are denied`() {
        assertThat(denial(token("app_id" to "Bad_App"))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("client_id" to "Bad Client"))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("sub" to "human-1"))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("scope" to ""))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("scope" to "member:read Bad Scope"))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("security_version" to 0))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("cnf" to null))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `only registered callers and registered scopes are accepted`() {
        val other = IamCallerResolver("https://iam.distracted.invalid",
            CallerRegistrationLookup { _, _ -> null }, clock)
        assertThat((runCatching { other.resolve(token()) }.exceptionOrNull() as CallerDeniedException).reason)
            .isEqualTo(CallerDenial.UNREGISTERED_CALLER)
        assertThat(denial(token("scope" to "member:read member:write")))
            .isEqualTo(CallerDenial.SCOPE_NOT_ALLOWED)
    }

    @Test fun `dpop binding is required when the registration demands it`() {
        assertThat(denial(token("cnf" to mapOf("jkt" to "short")))).isEqualTo(CallerDenial.DPOP_REQUIRED)
        val noDpop = CallerRegistration("dutch-development", "dutch-mobile", "https://api.dutch.invalid",
            setOf("member:read"), requiresDpop = false)
        val lenient = IamCallerResolver("https://iam.distracted.invalid",
            CallerRegistrationLookup { _, _ -> noDpop }, clock)
        assertThat(lenient.resolve(token()).scopes).containsExactly("member:read")
        // The provider contract requires a confirmation key even when DPoP is not demanded.
        assertThat((runCatching { lenient.resolve(token("cnf" to null)) }.exceptionOrNull()
            as CallerDeniedException).reason).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `assurance comes from the authentication context and unknown contexts fail closed`() {
        assertThat(resolver.resolve(token("acr" to "urn:distracted:aal2")).assurance).isEqualTo(Assurance.STRONG)
        assertThat(resolver.resolve(token("acr" to "urn:distracted:aal1")).assurance).isEqualTo(Assurance.SINGLE)
        assertThat(resolver.resolve(token("acr" to null)).assurance).isEqualTo(Assurance.NONE)
        assertThat(denial(token("acr" to "urn:distracted:custom"))).isEqualTo(CallerDenial.UNKNOWN_ASSURANCE)
    }

    @Test fun `absent contract claims are denied with their own reason`() {
        assertThat(denial(token("token_profile" to null))).isEqualTo(CallerDenial.PROFILE_MISMATCH)
        assertThat(denial(token("token_version" to null))).isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(token("subject_kind" to null))).isEqualTo(CallerDenial.SUBJECT_KIND_MISMATCH)
        assertThat(denial(token("security_version" to null))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("scope" to (1..33).joinToString(" ") { "s$it" })))
            .isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(token("aud" to listOf("https://api.dutch.invalid", "https://api.other.invalid"))))
            .isEqualTo(CallerDenial.AUDIENCE_MISMATCH)
        // auth_time may arrive as a numeric claim rather than a normalized Instant.
        assertThat(resolver.resolve(token("auth_time" to now.minusSeconds(5).epochSecond)).securityVersion)
            .isEqualTo(1)
    }

    @Test fun `registration configuration is validated`() {
        assertThatThrownBy {
            CallerRegistration("Bad_App", "dutch-mobile", "https://api.dutch.invalid", setOf("member:read"), true)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CallerRegistration("dutch-development", "dutch-mobile", "http://api.dutch.invalid", setOf("member:read"), true)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CallerRegistration("dutch-development", "dutch-mobile", "https://api.dutch.invalid", emptySet(), true)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private class FixedClock(private val fixed: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = fixed
    }
}
