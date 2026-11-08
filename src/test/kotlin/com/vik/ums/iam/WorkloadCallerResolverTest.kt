package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkloadCallerResolverTest {
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val issuer = "https://iam.example.test"
    private val audience = "https://ums.example.test/internal/iam/events"
    private val workload = "wl_0123456789abcdefghijkL"
    private val registration = WorkloadRegistration(
        workloadId = workload,
        appRegistrationId = "dutch-development",
        clientId = "iam-relay",
        audiences = setOf(audience),
        scopes = setOf("ums:onboarding:write"),
    )
    private val resolver = WorkloadCallerResolver(
        issuer, audience, "ums:onboarding:write", WorkloadRegistrations(listOf(registration)),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun claims(): MutableMap<String, Any> = mutableMapOf(
        "iss" to issuer,
        "sub" to workload,
        "aud" to audience,
        "iat" to now.epochSecond,
        "exp" to now.plusSeconds(300).epochSecond,
        "jti" to "0123456789abcdef",
        "client_id" to "iam-relay",
        "token_profile" to "workload_access",
        "token_version" to 1,
        "subject_kind" to "workload",
        "scope" to "ums:onboarding:write",
        "app_id" to "dutch-development",
        "security_version" to 1,
    )

    private fun jwt(typ: String = "at+jwt", alg: String = "ES256", kid: String = "key-1",
                    overrides: (MutableMap<String, Any>) -> Unit = {}): Jwt {
        val values = claims().also(overrides)
        return Jwt.withTokenValue("token")
            .header("typ", typ).header("alg", alg).header("kid", kid)
            .claims { it.putAll(values) }
            .issuedAt(Instant.ofEpochSecond((values["iat"] as Number).toLong()))
            .expiresAt(Instant.ofEpochSecond((values["exp"] as Number).toLong()))
            .build()
    }

    private fun denial(jwt: Jwt) = (resolver.resolve(jwt) as WorkloadResolution.Denied).reason

    @Test fun `a registered workload with the route audience and scope is accepted`() {
        val result = resolver.resolve(jwt())
        assertThat(result).isInstanceOf(WorkloadResolution.Accepted::class.java)
        val principal = (result as WorkloadResolution.Accepted).principal
        assertThat(principal.appRegistrationId).isEqualTo("dutch-development")
        assertThat(principal.workloadId).isEqualTo(workload)
        assertThat(principal.clientId).isEqualTo("iam-relay")
        assertThat(principal.scopes).containsExactly("ums:onboarding:write")
        assertThat(principal.securityVersion).isEqualTo(1)
    }

    @Test fun `header, claim set and issuer are exact`() {
        assertThat(denial(jwt(typ = "JWT"))).isEqualTo(WorkloadDenial.HEADER_MISMATCH)
        assertThat(denial(jwt(alg = ""))).isEqualTo(WorkloadDenial.HEADER_MISMATCH)
        assertThat(denial(jwt(kid = ""))).isEqualTo(WorkloadDenial.HEADER_MISMATCH)
        assertThat(denial(jwt { it["cnf"] = mapOf("jkt" to "x") })).isEqualTo(WorkloadDenial.CLAIM_SET_MISMATCH)
        assertThat(denial(jwt { it.remove("security_version") })).isEqualTo(WorkloadDenial.CLAIM_SET_MISMATCH)
        assertThat(denial(jwt { it["iss"] = "https://other.example.test" })).isEqualTo(WorkloadDenial.ISSUER_MISMATCH)
    }

    @Test fun `profile, version, subject kind and identity are exact`() {
        assertThat(denial(jwt { it["token_profile"] = "human_access" })).isEqualTo(WorkloadDenial.PROFILE_MISMATCH)
        assertThat(denial(jwt { it["token_version"] = 2 })).isEqualTo(WorkloadDenial.VERSION_UNSUPPORTED)
        assertThat(denial(jwt { it["subject_kind"] = "human" })).isEqualTo(WorkloadDenial.SUBJECT_KIND_MISMATCH)
        assertThat(denial(jwt { it["sub"] = "wl_short" })).isEqualTo(WorkloadDenial.WORKLOAD_ID_MISMATCH)
        assertThat(denial(jwt { it["jti"] = "short" })).isEqualTo(WorkloadDenial.JTI_MISMATCH)
        assertThat(denial(jwt { it["security_version"] = 0 })).isEqualTo(WorkloadDenial.SECURITY_VERSION_INVALID)
    }

    @Test fun `temporal rules reject expired, overlong, and future tokens`() {
        assertThat(denial(jwt {
            it["iat"] = now.minusSeconds(600).epochSecond
            it["exp"] = now.minusSeconds(61).epochSecond
        })).isEqualTo(WorkloadDenial.EXPIRED)
        assertThat(denial(jwt { it["exp"] = now.plusSeconds(901).epochSecond }))
            .isEqualTo(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        assertThat(denial(jwt { it["iat"] = now.plusSeconds(61).epochSecond }))
            .isEqualTo(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        // Within the allowed clock skew, a token is still accepted.
        val skew = Jwt.withTokenValue("token").header("typ", "at+jwt").header("alg", "ES256").header("kid", "key-1")
            .claims { it.putAll(claims().also { c -> c["iat"] = now.minusSeconds(30).epochSecond
                c["exp"] = now.plusSeconds(30).epochSecond }) }
            .issuedAt(now.minusSeconds(30)).expiresAt(now.plusSeconds(30)).build()
        assertThat(resolver.resolve(skew)).isInstanceOf(WorkloadResolution.Accepted::class.java)
    }

    @Test fun `audience, scope and binding are enforced`() {
        assertThat(denial(jwt { it["aud"] = "https://other.example.test/events" }))
            .isEqualTo(WorkloadDenial.AUDIENCE_MISMATCH)
        assertThat(denial(jwt { it["scope"] = "ums:onboarding:write other:scope" }))
            .isEqualTo(WorkloadDenial.SCOPE_NOT_ALLOWED)
        assertThat(denial(jwt { it["scope"] = "ums:onboarding:write ums:onboarding:write" }))
            .isEqualTo(WorkloadDenial.SCOPE_NOT_ALLOWED)
        assertThat(denial(jwt { it["scope"] = "other:scope" })).isEqualTo(WorkloadDenial.SCOPE_NOT_ALLOWED)
        assertThat(denial(jwt { it["app_id"] = "other-app" })).isEqualTo(WorkloadDenial.UNREGISTERED_WORKLOAD)
        assertThat(denial(jwt { it["client_id"] = "other-client" })).isEqualTo(WorkloadDenial.UNREGISTERED_WORKLOAD)
        assertThat(denial(jwt { it["sub"] = "wl_abcdefghijklmnopqrstuv" }))
            .isEqualTo(WorkloadDenial.UNREGISTERED_WORKLOAD)
    }

    @Test fun `configuration fails closed without registrations or with a bad audience`() {
        assertThatThrownBy { WorkloadRegistrations.parse(null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadRegistrations.parse("[]") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadRegistrations.parse("""[{"workloadId":"wl_x"}]""") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadCallerResolver(issuer, "http://insecure", "ums:onboarding:write",
            WorkloadRegistrations(listOf(registration))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
