package com.vik.ums.iam

/**
 * A reviewed caller registration. The tuple rules come from the pinned IAM conformance
 * requirements: issuer, app_id, client_id, single audience, allowed scopes, and DPoP binding.
 */
data class CallerRegistration(
    val appId: String,
    val clientId: String,
    val audience: String,
    val allowedScopes: Set<String>,
    val requiresDpop: Boolean,
) {
    init {
        require(appId.matches(APP_ID)) { "Registration app id is malformed" }
        require(clientId.matches(CLIENT_ID)) { "Registration client id is malformed" }
        require(audience.startsWith("https://")) { "Registration audience must be an HTTPS URI" }
        require(allowedScopes.isNotEmpty() && allowedScopes.all { it.matches(SCOPE) }) {
            "Registration scopes must be a non-empty bounded set"
        }
    }

    companion object {
        val APP_ID = Regex("[a-z][a-z0-9-]{2,63}")
        val CLIENT_ID = Regex("[a-z][a-z0-9-]{2,63}")
        val SCOPE = Regex("[a-z][a-z0-9:_-]{0,63}")
    }
}

/** Registered callers only; an unknown pair is denied without revealing which side is wrong. */
fun interface CallerRegistrationLookup {
    fun find(appId: String, clientId: String): CallerRegistration?
}

enum class Assurance { NONE, SINGLE, STRONG }

/**
 * Already-validated caller authority. References are opaque app-scoped strings; mapping them
 * to UMS scope references is an adapter concern, never a claim of a global identity.
 */
data class CallerContext(
    val appId: String,
    val subjectReference: String,
    val clientId: String,
    val assurance: Assurance,
    val securityVersion: Long,
    val authenticationContext: String?,
    val authenticationMethods: List<String>,
    val scopes: Set<String>,
    val tokenId: String,
)

/** Safe denial: a stable code only, never a claim value or token content. */
class CallerDeniedException(val reason: CallerDenial) : RuntimeException(reason.name)

enum class CallerDenial {
    HEADER_MISMATCH,
    ISSUER_MISMATCH,
    AUDIENCE_MISMATCH,
    EXPIRED,
    TEMPORAL_INCONSISTENCY,
    PROFILE_MISMATCH,
    VERSION_UNSUPPORTED,
    SUBJECT_KIND_MISMATCH,
    UNKNOWN_ASSURANCE,
    MALFORMED_CLAIM,
    UNREGISTERED_CALLER,
    SCOPE_NOT_ALLOWED,
    DPOP_REQUIRED,
}
