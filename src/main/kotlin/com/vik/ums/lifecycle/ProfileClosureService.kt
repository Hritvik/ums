package com.vik.ums.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileStateEvents
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Local lifecycle seam for app departure. No HTTP ingress or accepted IAM departure
 * adapter is wired, and no erasure happens here: closure only starts the published
 * retention period that a later expiry worker enforces.
 */
class ProfileClosureService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    environment: String,
    private val clock: Clock,
    private val retention: Duration = Duration.ofDays(90),
) {
    private val bindings = ProfilePersistence(jdbc)
    private val persistence = LifecyclePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    init { require(!retention.isNegative && !retention.isZero) { "Closure retention must be positive" } }

    fun close(fact: TrustedClosureFact): ClosureResult = transactions.execute {
        // Existing policy row provides the shared application lock: closure serializes with
        // edits, deletion, and finalization. Adoption is not required to close.
        val policy: AppProfilePolicy? = lockedPolicy(jdbc, mapper, fact.scope.application())
        require(policy != null) { "Application is not registered" }
        val current = bindings.binding(fact.scope)
            ?: return@execute ClosureResult(ClosureOutcome.UNAVAILABLE)
        if (current.lifecycle == Lifecycle.ERASED) return@execute ClosureResult(ClosureOutcome.UNAVAILABLE)

        val closedAt = fact.closedAt.truncatedTo(ChronoUnit.MICROS)
        val retainUntil = closedAt.plus(retention)
        val recorded = persistence.closure(fact.scope)
        if (recorded != null) {
            check(recorded.closedAt == closedAt) { "Closure time conflicts with the recorded closure" }
            val outcome = if (current.lifecycle == Lifecycle.CLOSED) ClosureOutcome.ALREADY_CLOSED
            else ClosureOutcome.RESTRICTED_RETAINED
            return@execute ClosureResult(outcome, current, recorded.retainUntil)
        }

        if (persistence.closeActive(fact.scope, closedAt, retainUntil)) {
            val after = bindings.binding(fact.scope)!!
            persistence.recordEvidence(fact.scope, "CLOSED", after.bindingRevision.toLong(), closedAt, retainUntil,
                clock.instant(), fact.eventId, fact.correlationId)
            events.emit(after, "CLOSED", fact.eventId, fact.correlationId)
            return@execute ClosureResult(ClosureOutcome.CLOSED, after, retainUntil)
        }

        // A deletion cause already restricts this profile. Retention is independent evidence:
        // it never changes lifecycle, revision, readiness, or the emitted stream.
        persistence.recordRetention(fact.scope, closedAt, retainUntil)
        persistence.recordEvidence(fact.scope, "CLOSED", current.bindingRevision.toLong(), closedAt, retainUntil,
            clock.instant(), fact.eventId, fact.correlationId)
        ClosureResult(ClosureOutcome.RESTRICTED_RETAINED, current, retainUntil)
    }!!
}
