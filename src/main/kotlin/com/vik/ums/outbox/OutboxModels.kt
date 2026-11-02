package com.vik.ums.outbox

import java.time.Duration
import java.util.UUID

/** One immutable outbox fact as the relay sees it. Identity never changes across retries. */
data class OutboxEvent(
    val eventId: UUID,
    val applicationId: String,
    val streamId: String,
    val streamRevision: Long,
    val eventType: String,
    val payload: String,
)

/** Safe failure classes only: no exception text or payload value ever leaves the service. */
enum class OutboxFailureClass { TRANSPORT_UNAVAILABLE, TRANSPORT_REJECTED, RELAY_INTERRUPTED }

/** The transport accepted or refused one fact. Any throw is classified by the relay. */
fun interface OutboxTransport {
    fun publish(event: OutboxEvent)
}

/** Result of one relay pass. */
data class RelayPass(val published: Int, val failed: Int, val quarantined: Int)

/**
 * Operator-facing delivery state. Ages and counts only: no event payload, subject, or token is
 * ever part of a signal.
 */
data class OutboxStatus(
    val unpublishedCount: Long,
    val oldestUnpublished: Duration?,
    val retryingCount: Long,
    val quarantinedCount: Long,
    val oldestQuarantine: Duration?,
    val blockedStreamCount: Long,
)
