package com.vik.ums.iam

import org.springframework.security.oauth2.jwt.Jwt
import java.math.BigInteger
import java.time.Clock
import java.time.Instant

/**
 * Validates the pinned IAM human access token contract before any handler sees a caller.
 *
 * The profile, version, subject kind, JOSE header and claim set are the provider's published
 * contract, not local inventions. Only `human_access` v1 with an `at+jwt` ES256 header is
 * accepted; workload and delegation profiles are unpublished, so they fail closed.
 */
class IamCallerResolver(
    private val issuer: String,
    private val registrations: CallerRegistrationLookup,
    private val clock: Clock = Clock.systemUTC(),
) {
    init { require(issuer.startsWith("https://")) { "Issuer must be an HTTPS URI" } }

    fun resolve(jwt: Jwt): CallerContext {
        requireHeader(jwt)
        // The provider schema forbids undeclared claims; accept an exact published set only.
        if (!PUBLISHED_CLAIMS.containsAll(jwt.claims.keys)) deny(CallerDenial.MALFORMED_CLAIM)

        if (jwt.claims["iss"] != issuer) deny(CallerDenial.ISSUER_MISMATCH)
        val issuedAt = jwt.issuedAt ?: deny(CallerDenial.MALFORMED_CLAIM)
        val expiresAt = jwt.expiresAt ?: deny(CallerDenial.MALFORMED_CLAIM)
        if (!expiresAt.isAfter(issuedAt)) deny(CallerDenial.TEMPORAL_INCONSISTENCY)
        if (!expiresAt.isAfter(Instant.now(clock))) deny(CallerDenial.EXPIRED)
        val authTime = publishedInstant(jwt, "auth_time")
        if (authTime != null && authTime.isAfter(issuedAt)) deny(CallerDenial.TEMPORAL_INCONSISTENCY)

        if (string(jwt, "token_profile") != HUMAN_PROFILE) deny(CallerDenial.PROFILE_MISMATCH)
        if (integer(jwt, "token_version") != 1L) deny(CallerDenial.VERSION_UNSUPPORTED)
        if (string(jwt, "subject_kind") != APP_SUBJECT) deny(CallerDenial.SUBJECT_KIND_MISMATCH)
        if (string(jwt, "jti")?.matches(JTI) != true) deny(CallerDenial.MALFORMED_CLAIM)
        // The provider publishes a single audience string; a list is contract-invalid.
        val audience = jwt.claims["aud"] as? String ?: deny(CallerDenial.AUDIENCE_MISMATCH)

        val appId = string(jwt, "app_id")?.takeIf { it.matches(CallerRegistration.APP_ID) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val clientId = string(jwt, "client_id")?.takeIf { it.matches(CallerRegistration.CLIENT_ID) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val subject = string(jwt, "sub")?.takeIf { it.matches(APP_SUBJECT_REFERENCE) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val securityVersion = integer(jwt, "security_version")?.takeIf { it > 0 }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val scopes = parseScopes(string(jwt, "scope") ?: deny(CallerDenial.MALFORMED_CLAIM))

        val registration = registrations.find(appId, clientId) ?: deny(CallerDenial.UNREGISTERED_CALLER)
        if (registration.audience != audience) deny(CallerDenial.AUDIENCE_MISMATCH)
        if (!registration.allowedScopes.containsAll(scopes)) deny(CallerDenial.SCOPE_NOT_ALLOWED)

        val jkt = confirmationKey(jwt)
        if (registration.requiresDpop && jkt == null) deny(CallerDenial.DPOP_REQUIRED)
        if (jkt == null) deny(CallerDenial.MALFORMED_CLAIM)
        if (!jkt.matches(DPOP_THUMBPRINT)) {
            deny(if (registration.requiresDpop) CallerDenial.DPOP_REQUIRED else CallerDenial.MALFORMED_CLAIM)
        }

        val acr = string(jwt, "acr")
        return CallerContext(appId, subject, clientId, assurance(acr), securityVersion, acr,
            authenticationMethods(jwt), scopes)
    }

    private fun requireHeader(jwt: Jwt) {
        if (jwt.headers["typ"] != HEADER_TYPE || jwt.headers["alg"] != HEADER_ALG) {
            deny(CallerDenial.HEADER_MISMATCH)
        }
        if ((jwt.headers["kid"] as? String)?.matches(KEY_ID) != true) deny(CallerDenial.HEADER_MISMATCH)
    }

    private fun assurance(acr: String?): Assurance {
        if (acr == null) return Assurance.NONE
        if (!acr.startsWith("urn:")) deny(CallerDenial.UNKNOWN_ASSURANCE)
        return when (acr.substringAfterLast(':')) {
            "aal3", "aal2" -> Assurance.STRONG
            "aal1" -> Assurance.SINGLE
            else -> deny(CallerDenial.UNKNOWN_ASSURANCE)
        }
    }

    private fun authenticationMethods(jwt: Jwt): List<String> {
        val raw = jwt.claims["amr"] ?: return emptyList()
        val values = raw as? Collection<*> ?: deny(CallerDenial.MALFORMED_CLAIM)
        if (values.isEmpty() || values.size > 8) deny(CallerDenial.MALFORMED_CLAIM)
        val methods = values.map { it as? String ?: deny(CallerDenial.MALFORMED_CLAIM) }
        if (methods.toSet().size != methods.size) deny(CallerDenial.MALFORMED_CLAIM)
        if (methods.any { !it.matches(AUTH_METHOD) }) deny(CallerDenial.MALFORMED_CLAIM)
        return methods
    }

    private fun parseScopes(scope: String): Set<String> {
        val parts = scope.split(' ')
        if (parts.size > 32 || parts.any { !it.matches(CallerRegistration.SCOPE) }) deny(CallerDenial.MALFORMED_CLAIM)
        return parts.toSet()
    }

    private fun confirmationKey(jwt: Jwt): String? =
        (jwt.claims["cnf"] as? Map<*, *>)?.get("jkt") as? String

    private fun publishedInstant(jwt: Jwt, name: String): Instant? {
        if (!jwt.claims.containsKey(name)) return null
        val seconds = integer(jwt, name) ?: deny(CallerDenial.MALFORMED_CLAIM)
        return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull() ?: deny(CallerDenial.MALFORMED_CLAIM)
    }

    private fun string(jwt: Jwt, name: String): String? =
        (jwt.claims[name] as? String)?.takeIf { it.isNotBlank() }

    /** Integers only: the provider schema forbids fractions and out-of-range values. */
    private fun integer(jwt: Jwt, name: String): Long? = when (val raw = jwt.claims[name]) {
        is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
        is BigInteger -> raw.takeIf { it.bitLength() < 63 }?.toLong()
        is Double -> raw.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble()
        }?.toLong()
        else -> null
    }

    private fun deny(reason: CallerDenial): Nothing = throw CallerDeniedException(reason)

    private companion object {
        const val HUMAN_PROFILE = "human_access"
        const val APP_SUBJECT = "app_subject"
        const val HEADER_TYPE = "at+jwt"
        const val HEADER_ALG = "ES256"
        val JTI = Regex("[A-Za-z0-9_-]{16,128}")
        val KEY_ID = Regex("[A-Za-z0-9_-]{8,128}")
        val APP_SUBJECT_REFERENCE = Regex("aps_[A-Za-z0-9_-]{22,128}")
        val DPOP_THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
        val AUTH_METHOD = Regex("[a-z][a-z0-9_-]{0,31}")
        val PUBLISHED_CLAIMS = setOf(
            "iss", "sub", "aud", "exp", "iat", "jti", "client_id", "token_profile", "token_version",
            "subject_kind", "scope", "app_id", "security_version", "acr", "amr", "auth_time", "cnf",
        )
    }
}
