package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.io.File
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipFile

/**
 * Conformance against the pinned IAM provider fixtures and their MUST rules: the accepted
 * token, every published rejected token, and the JOSE header contract.
 */
class IamCallerResolverTest {
    private val mapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val clock = FixedClock(now)
    private val archive = ZipFile(File("contracts/dependencies/iam/iam-contracts-1.0.0.zip"))

    private val claims: Map<String, Any?> = instance("fixtures/valid/token-claims.json")
    private val header: Map<String, Any> = instance("fixtures/valid/token-header.json")
        .mapValues { it.value as Any }

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

    private fun instance(path: String): Map<String, Any?> {
        val entry = archive.getEntry("iam-contracts-1.0.0/$path")
        val parsed = mapper.readValue(archive.getInputStream(entry).readBytes().decodeToString(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return parsed["instance"] as Map<String, Any?>
    }

    private fun jwtOf(claims: Map<String, Any?>, headers: Map<String, Any> = header): Jwt = Jwt(
        "token",
        Instant.ofEpochSecond((claims["iat"] as Number).toLong()),
        Instant.ofEpochSecond((claims["exp"] as Number).toLong()),
        headers,
        claims.filterValues { it != null }.mapValues { it.value as Any },
    )

    private fun with(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        claims.toMutableMap().apply {
            overrides.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
        }

    private fun denial(token: Jwt): CallerDenial =
        (runCatching { resolver.resolve(token) }.exceptionOrNull() as CallerDeniedException).reason

    @Test fun `the pinned provider token resolves to caller authority`() {
        val caller = resolver.resolve(jwtOf(claims))

        assertThat(caller.appId).isEqualTo("dutch-development")
        assertThat(caller.clientId).isEqualTo("dutch-mobile")
        assertThat(caller.subjectReference).isEqualTo("aps_0123456789abcdefghijkL")
        assertThat(caller.securityVersion).isEqualTo(1)
        assertThat(caller.assurance).isEqualTo(Assurance.STRONG)
        assertThat(caller.authenticationMethods).containsExactly("passkey")
        assertThat(caller.scopes).containsExactly("member:read")
        assertThat(caller.tokenId).isEqualTo("jti_0123456789abcdef")
    }

    @Test fun `a decoder-shaped token with list audience and normalized timestamps resolves`() {
        // Nimbus/Spring materialise aud as a one-element list and exp/iat as Instants.
        val decoderClaims = claims.toMutableMap().apply {
            put("aud", listOf("https://api.dutch.invalid"))
            put("exp", Instant.ofEpochSecond((claims["exp"] as Number).toLong()))
            put("iat", Instant.ofEpochSecond((claims["iat"] as Number).toLong()))
        }.mapValues { it.value as Any }
        val token = Jwt("token",
            Instant.ofEpochSecond((claims["iat"] as Number).toLong()),
            Instant.ofEpochSecond((claims["exp"] as Number).toLong()),
            header, decoderClaims)

        val caller = resolver.resolve(token)

        assertThat(caller.appId).isEqualTo("dutch-development")
        assertThat(caller.scopes).containsExactly("member:read")
    }

    @Test fun `every published rejected token fixture is denied`() {
        val expected = mapOf(
            "token-issuer-mismatch" to CallerDenial.ISSUER_MISMATCH,
            "token-audience-mismatch" to CallerDenial.AUDIENCE_MISMATCH,
            "token-app-mismatch" to CallerDenial.UNREGISTERED_CALLER,
            "token-client-mismatch" to CallerDenial.UNREGISTERED_CALLER,
            "token-scope-mismatch" to CallerDenial.SCOPE_NOT_ALLOWED,
            "token-auth-time-after-iat" to CallerDenial.TEMPORAL_INCONSISTENCY,
            "token-undeclared-field" to CallerDenial.MALFORMED_CLAIM,
        )
        expected.forEach { (name, reason) ->
            val claims = instance("fixtures/invalid/$name.json")
            assertThat(denial(jwtOf(claims))).describedAs(name).isEqualTo(reason)
        }
    }

    @Test fun `the jose header must be an access token header`() {
        assertThat(denial(jwtOf(claims, header + ("typ" to "JWT")))).isEqualTo(CallerDenial.HEADER_MISMATCH)
        assertThat(denial(jwtOf(claims, header + ("alg" to "RS256")))).isEqualTo(CallerDenial.HEADER_MISMATCH)
        assertThat(denial(jwtOf(claims, header + ("kid" to "short")))).isEqualTo(CallerDenial.HEADER_MISMATCH)
        assertThat(denial(jwtOf(claims, header - "kid"))).isEqualTo(CallerDenial.HEADER_MISMATCH)
    }

    @Test fun `expiry and clock ordering are enforced`() {
        // `exp` not after `iat` is also rejected by the Jwt type itself, which cannot represent
        // the provider's invalid fixture; the resolver keeps the defensive check for other issuers.

        assertThat(denial(jwtOf(with("iat" to now.minusSeconds(600).epochSecond,
            "exp" to now.minusSeconds(60).epochSecond)))).isEqualTo(CallerDenial.EXPIRED)
        val futureAuthTime = (claims["iat"] as Number).toLong() + 1
        assertThat(denial(jwtOf(with("auth_time" to futureAuthTime))))
            .isEqualTo(CallerDenial.TEMPORAL_INCONSISTENCY)
    }

    @Test fun `profile version and subject kind must match the published contract`() {
        assertThat(denial(jwtOf(with("token_profile" to "workload_access")))).isEqualTo(CallerDenial.PROFILE_MISMATCH)
        assertThat(denial(jwtOf(with("token_version" to 2)))).isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(jwtOf(with("token_version" to 1.9)))).isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(jwtOf(with("subject_kind" to "human")))).isEqualTo(CallerDenial.SUBJECT_KIND_MISMATCH)
    }

    @Test fun `malformed references scopes and numeric claims are denied`() {
        assertThat(denial(jwtOf(with("app_id" to "Bad_App")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("client_id" to "Bad Client")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("sub" to "human-1")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("jti" to "short")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("scope" to "")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("scope" to (1..33).joinToString(" ") { "s$it" }))))
            .isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("security_version" to 0)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("security_version" to 1e300)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("auth_time" to 1e300)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("aud" to listOf("https://api.dutch.invalid", "https://api.other.invalid")))))
            .isEqualTo(CallerDenial.AUDIENCE_MISMATCH)
        assertThat(denial(jwtOf(with("cnf" to mapOf("jkt" to DPOP, "extra" to "value")))))
            .isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("extra" to "value")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `absent contract claims are denied with their own reason`() {
        assertThat(denial(jwtOf(with("token_profile" to null)))).isEqualTo(CallerDenial.PROFILE_MISMATCH)
        assertThat(denial(jwtOf(with("token_version" to null)))).isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(jwtOf(with("subject_kind" to null)))).isEqualTo(CallerDenial.SUBJECT_KIND_MISMATCH)
        assertThat(denial(jwtOf(with("security_version" to null)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("jti" to null)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `only registered callers and registered scopes are accepted`() {
        val other = IamCallerResolver("https://iam.distracted.invalid",
            CallerRegistrationLookup { _, _ -> null }, clock)
        assertThat((runCatching { other.resolve(jwtOf(claims)) }.exceptionOrNull() as CallerDeniedException).reason)
            .isEqualTo(CallerDenial.UNREGISTERED_CALLER)
        assertThat(denial(jwtOf(with("scope" to "member:read member:write"))))
            .isEqualTo(CallerDenial.SCOPE_NOT_ALLOWED)
    }

    @Test fun `dpop binding is required when the registration demands it`() {
        assertThat(denial(jwtOf(with("cnf" to mapOf("jkt" to "short"))))).isEqualTo(CallerDenial.DPOP_REQUIRED)
        assertThat(denial(jwtOf(with("cnf" to null)))).isEqualTo(CallerDenial.DPOP_REQUIRED)
        val noDpop = CallerRegistration("dutch-development", "dutch-mobile", "https://api.dutch.invalid",
            setOf("member:read"), requiresDpop = false)
        val lenient = IamCallerResolver("https://iam.distracted.invalid",
            CallerRegistrationLookup { _, _ -> noDpop }, clock)
        assertThat(lenient.resolve(jwtOf(claims)).scopes).containsExactly("member:read")
        assertThat((runCatching { lenient.resolve(jwtOf(with("cnf" to null))) }.exceptionOrNull()
            as CallerDeniedException).reason).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `assurance comes from the authentication context and unknown contexts fail closed`() {
        assertThat(resolver.resolve(jwtOf(with("acr" to "urn:distracted:aal2"))).assurance).isEqualTo(Assurance.STRONG)
        assertThat(resolver.resolve(jwtOf(with("acr" to "urn:distracted:aal1"))).assurance).isEqualTo(Assurance.SINGLE)
        assertThat(resolver.resolve(jwtOf(with("acr" to null))).assurance).isEqualTo(Assurance.NONE)
        assertThat(denial(jwtOf(with("acr" to "urn:distracted:custom")))).isEqualTo(CallerDenial.UNKNOWN_ASSURANCE)
        assertThat(denial(jwtOf(with("acr" to "aal2")))).isEqualTo(CallerDenial.UNKNOWN_ASSURANCE)
        assertThat(denial(jwtOf(with("acr" to "")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `authentication methods are validated rather than coerced`() {
        assertThat(denial(jwtOf(with("amr" to listOf<String>())))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("amr" to listOf("passkey", "passkey"))))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("amr" to listOf("PASSKEY"))))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("amr" to "passkey")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
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

        @Test fun `numeric claims accept only exact integral values`() {
        assertThat(resolver.resolve(jwtOf(with("token_version" to BigInteger.ONE))).securityVersion).isEqualTo(1)
        assertThat(resolver.resolve(jwtOf(with("security_version" to 1.0))).securityVersion).isEqualTo(1)
        assertThat(denial(jwtOf(with("token_version" to BigInteger("9223372036854775808")))))
            .isEqualTo(CallerDenial.VERSION_UNSUPPORTED)
        assertThat(denial(jwtOf(with("security_version" to BigInteger("-1")))))
            .isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("security_version" to 1.5)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `claim value types are not coerced`() {
        assertThat(denial(jwtOf(with("sub" to 123)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("scope" to listOf("member:read"))))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("app_id" to 1)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("aud" to mapOf("value" to "https://api.dutch.invalid")))))
            .isEqualTo(CallerDenial.AUDIENCE_MISMATCH)
        assertThat(denial(jwtOf(with("cnf" to "not-an-object")))).isEqualTo(CallerDenial.DPOP_REQUIRED)
        assertThat(denial(jwtOf(with("cnf" to emptyMap<String, Any>())))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
    }

    @Test fun `header members must be present and textual`() {
        assertThat(denial(jwtOf(claims, header - "typ"))).isEqualTo(CallerDenial.HEADER_MISMATCH)
        assertThat(denial(jwtOf(claims, header - "alg"))).isEqualTo(CallerDenial.HEADER_MISMATCH)
        assertThat(denial(jwtOf(claims, header + ("kid" to 42)))).isEqualTo(CallerDenial.HEADER_MISMATCH)
    }

    @Test fun `authentication method bounds are enforced`() {
        assertThat(denial(jwtOf(with("amr" to (1..9).map { "m$it" })))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("amr" to listOf("passkey", 7))))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(resolver.resolve(jwtOf(with("amr" to null))).authenticationMethods).isEmpty()
    }

    @Test fun `nested and boundary claim shapes are rejected`() {
        assertThat(denial(jwtOf(with("cnf" to mapOf("jkt" to 42))))).isEqualTo(CallerDenial.DPOP_REQUIRED)
        assertThat(denial(jwtOf(with("jti" to 42)))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("client_id" to "")))).isEqualTo(CallerDenial.MALFORMED_CLAIM)
        assertThat(denial(jwtOf(with("iss" to 42)))).isEqualTo(CallerDenial.ISSUER_MISMATCH)
        assertThat(denial(jwtOf(with("scope" to (1..32).joinToString(" ") { "s$it" })))).isEqualTo(CallerDenial.SCOPE_NOT_ALLOWED)
        assertThat(resolver.resolve(jwtOf(with("auth_time" to Instant.ofEpochSecond(now.epochSecond - 5)))).tokenId)
            .isEqualTo("jti_0123456789abcdef")
    }

    private companion object {
        const val DPOP = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    }

    private class FixedClock(private val fixed: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = fixed
    }
}
