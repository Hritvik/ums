package com.vik.ums.iam

import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant

/**
 * Validates the pinned IAM human access token contract before any handler sees a caller.
 *
 * The profile, version, subject kind and claim names are the provider's published contract,
 * not local inventions. Only `human_access` v1 is accepted; workload and delegation profiles
 * are unpublished, so they fail closed instead of being guessed.
 */
class IamCallerResolver(
    private val issuer: String,
    private val registrations: CallerRegistrationLookup,
    private val clock: Clock = Clock.systemUTC(),
) {
    init { require(issuer.startsWith("https://")) { "Issuer must be an HTTPS URI" } }

    fun resolve(jwt: Jwt): CallerContext {
        if (jwt.claims["iss"] != issuer) deny(CallerDenial.ISSUER_MISMATCH)
        // Spring normalizes the registered timestamp claims to Instant; auth_time stays custom.
        val issuedAt = jwt.issuedAt ?: deny(CallerDenial.MALFORMED_CLAIM)
        val expiresAt = jwt.expiresAt ?: deny(CallerDenial.MALFORMED_CLAIM)
        if (!expiresAt.isAfter(issuedAt)) deny(CallerDenial.TEMPORAL_INCONSISTENCY)
        if (!expiresAt.isAfter(Instant.now(clock))) deny(CallerDenial.EXPIRED)
        val authTime = when (val raw = jwt.claims["auth_time"]) {
            is Instant -> raw
            is Number -> Instant.ofEpochSecond(raw.toLong())
            null -> null
            else -> deny(CallerDenial.MALFORMED_CLAIM)
        }
        if (authTime != null && authTime.isAfter(issuedAt)) deny(CallerDenial.TEMPORAL_INCONSISTENCY)

        if (string(jwt, "token_profile") != HUMAN_PROFILE) deny(CallerDenial.PROFILE_MISMATCH)
        if (number(jwt, "token_version") != 1L) deny(CallerDenial.VERSION_UNSUPPORTED)
        if (string(jwt, "subject_kind") != APP_SUBJECT) deny(CallerDenial.SUBJECT_KIND_MISMATCH)

        val appId = string(jwt, "app_id")?.takeIf { it.matches(CallerRegistration.APP_ID) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val clientId = string(jwt, "client_id")?.takeIf { it.matches(CallerRegistration.CLIENT_ID) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val subject = string(jwt, "sub")?.takeIf { it.matches(APP_SUBJECT_REFERENCE) }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val securityVersion = number(jwt, "security_version")?.takeIf { it > 0 }
            ?: deny(CallerDenial.MALFORMED_CLAIM)
        val scope = string(jwt, "scope") ?: deny(CallerDenial.MALFORMED_CLAIM)
        val scopes = parseScopes(scope)

        val registration = registrations.find(appId, clientId) ?: deny(CallerDenial.UNREGISTERED_CALLER)
        if (registration.audience != audience(jwt)) deny(CallerDenial.AUDIENCE_MISMATCH)
        if (!registration.allowedScopes.containsAll(scopes)) deny(CallerDenial.SCOPE_NOT_ALLOWED)

        val jkt = confirmationKey(jwt) ?: deny(CallerDenial.MALFORMED_CLAIM)
        if (!jkt.matches(DPOP_THUMBPRINT)) {
            deny(if (registration.requiresDpop) CallerDenial.DPOP_REQUIRED else CallerDenial.MALFORMED_CLAIM)
        }

        val acr = string(jwt, "acr")
        val assurance = assurance(acr)
        val amr = (jwt.claims["amr"] as? Collection<*>)?.mapNotNull { it as? String } ?: emptyList()
        return CallerContext(appId, subject, clientId, assurance, securityVersion, acr, amr, scopes)
    }

    private fun assurance(acr: String?): Assurance = when {
        acr == null -> Assurance.NONE
        acr.endsWith("aal3") || acr.endsWith("aal2") -> Assurance.STRONG
        acr.endsWith("aal1") -> Assurance.SINGLE
        else -> deny(CallerDenial.UNKNOWN_ASSURANCE)
    }

    private fun parseScopes(scope: String): Set<String> {
        val parts = scope.split(' ')
        if (parts.size > 32 || parts.any { !it.matches(CallerRegistration.SCOPE) }) deny(CallerDenial.MALFORMED_CLAIM)
        return parts.toSet()
    }

    private fun audience(jwt: Jwt): String? = when (val raw = jwt.claims["aud"]) {
        is String -> raw
        is Collection<*> -> raw.singleOrNull() as? String
        else -> null
    }

    private fun confirmationKey(jwt: Jwt): String? =
        (jwt.claims["cnf"] as? Map<*, *>)?.get("jkt") as? String

    private fun string(jwt: Jwt, name: String): String? =
        (jwt.claims[name] as? String)?.takeIf { it.isNotBlank() }

    private fun number(jwt: Jwt, name: String): Long? = when (val raw = jwt.claims[name]) {
        is Number -> raw.toLong()
        else -> null
    }

    private fun deny(reason: CallerDenial): Nothing = throw CallerDeniedException(reason)

    private companion object {
        const val HUMAN_PROFILE = "human_access"
        const val APP_SUBJECT = "app_subject"
        val APP_SUBJECT_REFERENCE = Regex("aps_[A-Za-z0-9_-]{22,128}")
        val DPOP_THUMBPRINT = Regex("[A-Za-z0-9_-]{43}")
    }
}
