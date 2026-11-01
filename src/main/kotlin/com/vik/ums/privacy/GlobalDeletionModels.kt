package com.vik.ums.privacy

import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.ProfileScope
import java.time.Instant
import java.util.UUID

/** Registered IAM lifecycle adapter authority. A trusted local seam, not a token or provider proof. */
data class TrustedProviderAuthority(val app: AppScope, val actorReference: UUID)

/** Provider-owned deletion-pending fact. The deadline is authoritative IAM time, never a local window. */
data class GlobalPendingFact(
    val scope: ProfileScope,
    val sourceRevision: Long,
    val acceptedAt: Instant,
    val deadline: Instant,
    val eventId: UUID,
    val correlationId: String,
) {
    init {
        require(sourceRevision > 0)
        require(deadline.isAfter(acceptedAt))
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}

data class GlobalCancellationFact(
    val scope: ProfileScope,
    val sourceRevision: Long,
    val occurredAt: Instant,
    val eventId: UUID,
    val correlationId: String,
) {
    init {
        require(sourceRevision > 0)
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}

/** Authoritative final deletion. It erases now and opens no new cancellation window. */
data class GlobalFinalFact(
    val scope: ProfileScope,
    val sourceRevision: Long,
    val occurredAt: Instant,
    val eventId: UUID,
    val correlationId: String,
) {
    init {
        require(sourceRevision > 0)
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}

enum class GlobalDeletionOutcome {
    APPLIED,

    /** The fact is at or behind the applied source revision, or the profile is already erased. */
    STALE,

    /** No profile exists for this scope. */
    UNAVAILABLE,

    /** An avatar association still exists; cleanup is not confirmed. */
    CONTENT_BLOCKED,
}

data class GlobalDeletionResult(val outcome: GlobalDeletionOutcome, val state: BindingState? = null)
