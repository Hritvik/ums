package com.vik.ums.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.notices.suppressNotices
import com.vik.ums.privacy.invalidateExportsFor
import com.vik.ums.recovery.ErasureFeed
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
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
    private val environment: String,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val persistence = LifecyclePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)
    private val erasureJournal = ErasureFeed(jdbc)

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
        val profileReference = current.profileReference
            ?: return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        val generation = current.generation
            ?: return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        // The earliest irreversible deadline governs. A pending deletion that lands earlier
        // owns erasure even when its worker is late; a later one cannot extend retention.
        val earliestCause = listOfNotNull(current.deletionCauses.appOnly, current.deletionCauses.iamGlobal)
            .map { Instant.parse(it.deadline) }.minOrNull()
        if (earliestCause != null && earliestCause.isBefore(retainUntil))
            return@execute ExpiryResult(ExpiryOutcome.SKIPPED, current)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (now.isBefore(retainUntil)) return@execute ExpiryResult(ExpiryOutcome.NOT_DUE, current)

        // The association is dropped and its reference is kept as a provider cleanup debt.
        val contentReference = jdbc.queryForObject("""select avatar_reference from ums_profile_bindings
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""", String::class.java,
            fact.scope.issuerEnvironmentRef, fact.scope.appReference, fact.scope.subjectReference)

        val revision = Math.addExact(current.bindingRevision.toLong(), 1)
        if (!persistence.expireErased(fact.scope, revision, current.bindingRevision.toLong())) {
            return@execute ExpiryResult(ExpiryOutcome.SKIPPED, bindings.binding(fact.scope))
        }
        val superseded = mapper.writeValueAsString(
            listOfNotNull(
                current.deletionCauses.appOnly?.let {
                    mapOf("type" to "APP_ONLY", "reference" to it.requestReference.toString())
                },
                current.deletionCauses.iamGlobal?.let {
                    mapOf("type" to "IAM_GLOBAL", "reference" to it.requestReference.toString())
                },
            ))
        erasureJournal.record(fact.scope, profileReference, generation.toLong(), revision, now,
            "CLOSURE_EXPIRY", null)
        persistence.clearCauses(fact.scope)
        jdbc.update("""update ums_profile_deletion_requests set status = 'SUPERSEDED',
            operation_revision = operation_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and status = 'PENDING'""",
            fact.scope.issuerEnvironmentRef, fact.scope.appReference, fact.scope.subjectReference)
        persistence.recordErasedObligation(fact.scope, "CLOSURE_EXPIRY", null, profileReference,
            generation.toLong(), recorded.closedAt, superseded, revision, now, contentReference)
        invalidateExportsFor(jdbc, fact.scope, now)
        suppressNotices(jdbc, mapper, environment, fact.scope, fact.correlationId, now)
        persistence.recordEvidence(fact.scope, "EXPIRED", revision, now, null, now, fact.eventId, fact.correlationId)

        val after = bindings.binding(fact.scope)!!
        check(after.lifecycle == Lifecycle.ERASED && after.readiness == Readiness.PROFILE_REQUIRED)
        events.emit(after, "ERASED", fact.eventId, fact.correlationId)
        ExpiryResult(ExpiryOutcome.EXPIRED, after)
    }!!

    /** False when an avatar association still exists, so erasure cannot drop the only pointer. */}
