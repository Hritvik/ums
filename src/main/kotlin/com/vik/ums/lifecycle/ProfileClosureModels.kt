package com.vik.ums.lifecycle

import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.ProfileScope
import java.time.Instant
import java.util.UUID

/**
 * Trusted application seam, not an IAM wire format or proof of authority. Production
 * departure delivery is a separate adapter gate.
 */
data class TrustedClosureFact(
    val scope: ProfileScope,
    val closedAt: Instant,
    val eventId: UUID,
    val correlationId: String,
) {
    init { require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

enum class ClosureOutcome {
    /** The profile became closed and its retention deadline was published. */
    CLOSED,

    /** The same authoritative closure time was already recorded; nothing changed. */
    ALREADY_CLOSED,

    /** Closure was recorded, but an independent deletion cause already restricts the profile. */
    RESTRICTED_RETAINED,

    /** There is no closable profile for this scope. */
    UNAVAILABLE,
}

data class ClosureResult(
    val outcome: ClosureOutcome,
    val state: BindingState? = null,
    val retainUntil: Instant? = null,
)
