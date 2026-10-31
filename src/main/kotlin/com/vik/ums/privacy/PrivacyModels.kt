package com.vik.ums.privacy

import com.vik.ums.profiles.ProfileScope
import java.time.Instant
import java.util.UUID

/** Trusted local proof, not an IAM claim format. Production authority is not wired. */
data class TrustedPrivacyAuthority(
    val scope: ProfileScope, val actorReference: UUID, val purpose: PrivacyPurpose,
    val authenticatedAt: Instant, val strong: Boolean, val expiresAt: Instant,
)
enum class PrivacyPurpose { REQUEST_DELETION, CANCEL_DELETION, READ_DELETION_STATUS }
data class DeletionRequestCommand(val idempotencyKey: UUID, val expectedVersion: Long, val correlationId: String) {
    init { require(expectedVersion >= 0); require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}
data class DeletionCancellationCommand(
    val requestReference: UUID, val idempotencyKey: UUID, val expectedVersion: Long, val correlationId: String,
) {
    init { require(expectedVersion >= 0); require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}
data class PrivacyAccepted(val operationReference: UUID, val operationRevision: String, val status: String = "ACCEPTED")
data class DeletionRequestStatus(
    val operationReference: UUID, val operationRevision: String, val bindingRevision: String,
    val status: String, val acceptedAt: String, val deadline: String, val cancellationEligible: Boolean,
)
internal data class StoredDeletionRequest(
    val reference: UUID, val profileReference: UUID, val generation: String, val revision: String,
    val status: String, val acceptedAt: Instant, val deadline: Instant,
)
