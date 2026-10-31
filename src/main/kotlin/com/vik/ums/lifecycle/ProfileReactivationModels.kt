package com.vik.ums.lifecycle

import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.ProfileScope
import java.util.UUID

/**
 * Trusted application seam, not an IAM wire format or proof of authority. Production
 * reactivation authority is a separate adapter gate.
 */
data class TrustedReactivationFact(
    val scope: ProfileScope,
    val eventId: UUID,
    val correlationId: String,
) {
    init { require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

enum class ReactivationOutcome {
    /** The profile reopened and readiness was recomputed from the current policy. */
    REACTIVATED,

    /** The profile was already active; nothing changed. */
    ALREADY_ACTIVE,

    /** The profile cannot be reopened for any reason; callers cannot tell which. */
    UNAVAILABLE,
}

data class ReactivationResult(
    val outcome: ReactivationOutcome,
    val state: BindingState? = null,
)
