package com.vik.ums.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.notices.NoticePersistence
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileStateEvents
import com.vik.ums.profiles.Readiness
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Local closure-expiry seam. This is expiry of an already published retention
 * promise, not a new deletion request: it opens no grace period, grants no
 * cancellation window, and reuses the same erased tombstone and event as the
 * deletion finalizer.
 */
class ProfileExpiryService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    environment: String,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val persistence = LifecyclePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun expire(fact: TrustedExpiryFact): ExpiryResult = transactions.execute {
        // The registered policy row is the shared application lock. Adoption is not
        // required: expiry executes an obligation accepted while the app was adopted.
        require(lockedPolicy(jdbc, mapper, fact.scope.application()) != null) { "Application is not registered" }
        val current = bindings.binding(fact.scope)
            ?: return@execute ExpiryResult(ExpiryOutcome.UNAVAILABLE)
        if (current.lifecycle != Lifecycle.CLOSED && current.lifecycle != Lifecycle.DELETION_PENDING)
            return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        val recorded = persistence.closure(fact.scope)
            ?: return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        val retainUntil = recorded.retainUntil
            ?: return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        // The earliest irreversible deadline governs. A pending deletion that lands earlier
        // owns erasure even when its worker is late; a later one cannot extend retention.
        val earliestCause = listOfNotNull(current.deletionCauses.appOnly, current.deletionCauses.iamGlobal)
            .map { Instant.parse(it.deadline) }.minOrNull()
        if (earliestCause != null && earliestCause.isBefore(retainUntil))
            return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (now.isBefore(retainUntil)) return@execute ExpiryResult(ExpiryOutcome.NOT_DUE, current)

        val revision = Math.addExact(current.bindingRevision.toLong(), 1)
        check(persistence.expireErased(fact.scope, revision)) { "Profile is no longer a retained generation" }
        persistence.clearCauses(fact.scope)
        persistence.recordExpiryFence(fact.scope, current.profileReference!!, current.generation!!.toLong(),
            recorded.closedAt, revision, now)
        NoticePersistence(jdbc).suppress(fact.scope, now)
        persistence.recordEvidence(fact.scope, "EXPIRED", revision, now, null, now, fact.eventId, fact.correlationId)

        val after = bindings.binding(fact.scope)!!
        check(after.lifecycle == Lifecycle.ERASED && after.readiness == Readiness.PROFILE_REQUIRED)
        events.emit(after, "ERASED", fact.eventId, fact.correlationId)
        ExpiryResult(ExpiryOutcome.EXPIRED, after)
    }!!
}
