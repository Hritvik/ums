package com.vik.ums.lifecycle

import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.ProfileScope
import java.util.UUID

/**
 * Trusted maintenance seam for closure expiry. It executes an already published
 * retention obligation; it cannot invent intent or extend a deadline.
 */
data class TrustedExpiryFact(
    val scope: ProfileScope,
    val eventId: UUID,
    val correlationId: String,
) {
    init { require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

enum class ExpiryOutcome {
    /** The retained profile was erased and its generation fenced. */
    EXPIRED,

    /** The published retention deadline has not passed yet. */
    NOT_DUE,

    /** The profile is absent, already erased, or restricted by another obligation. */
    SKIPPED,

    /** An avatar association still exists; the content owner has not confirmed cleanup. */
    CONTENT_BLOCKED,

    /** No profile exists for this scope. */
    UNAVAILABLE,
}

data class ExpiryResult(
    val outcome: ExpiryOutcome,
    val state: BindingState? = null,
)
