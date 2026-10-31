package com.vik.ums.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfileFieldsPersistence
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileStateEvents
import com.vik.ums.profiles.Readiness
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Local lifecycle seam for deliberate reactivation. Login, edits, replay, and
 * duplicate onboarding facts cannot reopen a retained profile; only this explicit
 * command can, and only before the published retention deadline.
 */
class ProfileReactivationService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    environment: String,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val persistence = LifecyclePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun reactivate(fact: TrustedReactivationFact): ReactivationResult = transactions.execute {
        val policy: AppProfilePolicy = lockedPolicy(jdbc, mapper, fact.scope.application())
            ?: return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE)
        val current = bindings.binding(fact.scope)
            ?: return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE)

        when (current.lifecycle) {
            Lifecycle.ACTIVE -> return@execute ReactivationResult(ReactivationOutcome.ALREADY_ACTIVE, current)
            Lifecycle.CLOSED -> Unit
            Lifecycle.DELETION_PENDING, Lifecycle.ERASED ->
                return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE, current)
        }
        val blocked = !policy.adopted ||
            current.deletionCauses.appOnly != null || current.deletionCauses.iamGlobal != null
        if (blocked) return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE, current)

        // A legacy closed row without a published deadline has no defensible reopen window.
        val retainUntil = persistence.closure(fact.scope)?.retainUntil
            ?: return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE, current)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (!now.isBefore(retainUntil)) return@execute ReactivationResult(ReactivationOutcome.UNAVAILABLE, current)

        val present = fields.fields(fact.scope).keys
        val readiness = if (policy.requiredFields.all { it in present }) Readiness.PROFILE_READY
        else Readiness.PROFILE_REQUIRED
        check(persistence.reactivate(fact.scope, readiness.name)) { "Profile is no longer closed" }

        val after = bindings.binding(fact.scope)!!
        persistence.recordEvidence(fact.scope, "REACTIVATED", after.bindingRevision.toLong(), now, null,
            now, fact.eventId, fact.correlationId)
        events.emit(after, "REACTIVATED", fact.eventId, fact.correlationId)
        ReactivationResult(ReactivationOutcome.REACTIVATED, after)
    }!!
}
