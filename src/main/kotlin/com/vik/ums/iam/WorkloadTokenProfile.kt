package com.vik.ums.iam

import org.springframework.security.oauth2.jwt.Jwt
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The accepted IAM `workload_access` v1 profile and the onboarding-ingress slice of it.
 *
 * Pinned 2026-11-08 against IAM `docs/identity-platform/deployment/workload-token-profile.md`
 * (decision `iam-workload-credential-001`, accepted by the portfolio owner). A workload token is
 * never a human caller: it has no user subject, no recent-authentication assurance, and no
 * readiness follows from it.
 */
object WorkloadTokenProfile {
    const val PROFILE = "workload_access"
    const val VERSION = 1L
    const val SUBJECT_KIND = "workload"
    const val MAX_LIFETIME_SECONDS = 900L
    const val CLOCK_SKEW_SECONDS = 60L

    /** Exactly the claims the profile allows; an undeclared or missing claim is rejected. */
    val CLAIMS = setOf(
        "iss", "sub", "aud", "iat", "exp", "jti", "client_id", "token_profile",
        "token_version", "subject_kind", "scope", "app_id", "security_version",
    )

    val WORKLOAD_ID = Regex("^wl_[A-Za-z0-9_-]{22,128}$")
    val APP_ID = Regex("^[a-z][a-z0-9-]{2,63}$")
    val CLIENT_ID = Regex("^[a-z][a-z0-9-]{2,63}$")
    val SCOPE = Regex("^[a-z][a-z0-9:_-]{0,63}$")
    val JTI = Regex("^[A-Za-z0-9_-]{16,128}$")
    val KEY_ID = Regex("^[A-Za-z0-9_-]{8,128}$")
    val AUDIENCE = Regex("^https://[^\\s?#]+$")

    /** The IAM snapshot route's required audience and scope, for the repair client. */
    const val SNAPSHOT_AUDIENCE = "https://iam.distracted.invalid/internal/onboarding"
    const val SNAPSHOT_SCOPE = "iam:onboarding:read"
    const val SNAPSHOT_PROFILE = "registeredWorkloadToken"
}

data class WorkloadPrincipal(
    val appRegistrationId: String,
    val workloadId: String,
    val clientId: String,
    val scopes: Set<String>,
    val securityVersion: Long,
    val authenticatedAt: Instant,
    val jti: String,
    val expiresAt: Instant,
)

enum class WorkloadDenial {
    HEADER_MISMATCH,
    CLAIM_SET_MISMATCH,
    ISSUER_MISMATCH,
    TEMPORAL_INCONSISTENCY,
    EXPIRED,
    PROFILE_MISMATCH,
    VERSION_UNSUPPORTED,
    SUBJECT_KIND_MISMATCH,
    WORKLOAD_ID_MISMATCH,
    JTI_MISMATCH,
    AUDIENCE_MISMATCH,
    SCOPE_NOT_ALLOWED,
    UNREGISTERED_WORKLOAD,
    SECURITY_VERSION_INVALID,
}

sealed interface WorkloadResolution {
    data class Accepted(val principal: WorkloadPrincipal) : WorkloadResolution
    data class Denied(val reason: WorkloadDenial) : WorkloadResolution
}

/**
 * Validates a workload token for one internal route. The signature and issuer are checked by the
 * resource-server decoder; this resolver enforces the exact profile, the route audience and scope,
 * and the registered workload binding. Any mismatch is a denial, never a partial principal.
 */
class WorkloadCallerResolver(
    private val issuer: String,
    private val audience: String,
    private val requiredScope: String,
    private val registrations: WorkloadRegistrations,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(URI.create(issuer).let { it.scheme == "https" && !it.host.isNullOrBlank() }) {
            "Workload issuer must be an HTTPS URI"
        }
        require(audience.matches(WorkloadTokenProfile.AUDIENCE)) { "Workload audience must be an HTTPS URI" }
        require(requiredScope.matches(WorkloadTokenProfile.SCOPE)) { "Required scope is malformed" }
    }

    fun resolve(jwt: Jwt): WorkloadResolution {
        if (jwt.headers["typ"] != "at+jwt" || jwt.headers["alg"] != "ES256" ||
            (jwt.headers["kid"] as? String)?.matches(WorkloadTokenProfile.KEY_ID) != true) {
            return WorkloadResolution.Denied(WorkloadDenial.HEADER_MISMATCH)
        }
        if (jwt.claims.keys != WorkloadTokenProfile.CLAIMS) {
            return WorkloadResolution.Denied(WorkloadDenial.CLAIM_SET_MISMATCH)
        }
        if (jwt.claims["iss"] != issuer) return WorkloadResolution.Denied(WorkloadDenial.ISSUER_MISMATCH)

        val issuedAt = jwt.issuedAt ?: return WorkloadResolution.Denied(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        val expiresAt = jwt.expiresAt ?: return WorkloadResolution.Denied(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        if (!expiresAt.isAfter(issuedAt) ||
            Duration.between(issuedAt, expiresAt).seconds > WorkloadTokenProfile.MAX_LIFETIME_SECONDS) {
            return WorkloadResolution.Denied(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        }
        val now = Instant.now(clock)
        if (!issuedAt.isBefore(now.plusSeconds(WorkloadTokenProfile.CLOCK_SKEW_SECONDS))) {
            return WorkloadResolution.Denied(WorkloadDenial.TEMPORAL_INCONSISTENCY)
        }
        if (!expiresAt.isAfter(now.minusSeconds(WorkloadTokenProfile.CLOCK_SKEW_SECONDS))) {
            return WorkloadResolution.Denied(WorkloadDenial.EXPIRED)
        }

        if (string(jwt, "token_profile") != WorkloadTokenProfile.PROFILE) {
            return WorkloadResolution.Denied(WorkloadDenial.PROFILE_MISMATCH)
        }
        if (integer(jwt, "token_version") != WorkloadTokenProfile.VERSION) {
            return WorkloadResolution.Denied(WorkloadDenial.VERSION_UNSUPPORTED)
        }
        if (string(jwt, "subject_kind") != WorkloadTokenProfile.SUBJECT_KIND) {
            return WorkloadResolution.Denied(WorkloadDenial.SUBJECT_KIND_MISMATCH)
        }
        val workloadId = string(jwt, "sub")?.takeIf { it.matches(WorkloadTokenProfile.WORKLOAD_ID) }
            ?: return WorkloadResolution.Denied(WorkloadDenial.WORKLOAD_ID_MISMATCH)
        val jti = string(jwt, "jti")?.takeIf { it.matches(WorkloadTokenProfile.JTI) }
            ?: return WorkloadResolution.Denied(WorkloadDenial.JTI_MISMATCH)
        if (jwt.audience?.singleOrNull() != audience) {
            return WorkloadResolution.Denied(WorkloadDenial.AUDIENCE_MISMATCH)
        }

        val appId = string(jwt, "app_id")?.takeIf { it.matches(WorkloadTokenProfile.APP_ID) }
            ?: return WorkloadResolution.Denied(WorkloadDenial.UNREGISTERED_WORKLOAD)
        val clientId = string(jwt, "client_id")?.takeIf { it.matches(WorkloadTokenProfile.CLIENT_ID) }
            ?: return WorkloadResolution.Denied(WorkloadDenial.UNREGISTERED_WORKLOAD)
        val securityVersion = integer(jwt, "security_version")?.takeIf { it > 0 }
            ?: return WorkloadResolution.Denied(WorkloadDenial.SECURITY_VERSION_INVALID)

        val scopes = parseScopes(string(jwt, "scope"))
            ?: return WorkloadResolution.Denied(WorkloadDenial.SCOPE_NOT_ALLOWED)
        val registration = registrations.find(appId, clientId, workloadId)
            ?: return WorkloadResolution.Denied(WorkloadDenial.UNREGISTERED_WORKLOAD)
        if (audience !in registration.audiences || !registration.scopes.containsAll(scopes) ||
            requiredScope !in scopes) {
            return WorkloadResolution.Denied(WorkloadDenial.SCOPE_NOT_ALLOWED)
        }

        return WorkloadResolution.Accepted(WorkloadPrincipal(
            appRegistrationId = appId,
            workloadId = workloadId,
            clientId = clientId,
            scopes = scopes,
            securityVersion = securityVersion,
            authenticatedAt = issuedAt,
            jti = jti,
            expiresAt = expiresAt,
        ))
    }

    /** Space-separated, sorted, unique, bounded; anything else is a denial erring towards closed. */
    private fun parseScopes(raw: String?): Set<String>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(' ')
        if (parts.size > 32 || parts.any { !it.matches(WorkloadTokenProfile.SCOPE) }) return null
        if (parts != parts.sorted() || parts.toSet().size != parts.size) return null
        return parts.toSet()
    }

    private fun string(jwt: Jwt, name: String): String? = jwt.claims[name] as? String

    private fun integer(jwt: Jwt, name: String): Long? = when (val value = jwt.claims[name]) {
        is Number -> value.toLong()
        else -> null
    }
}
