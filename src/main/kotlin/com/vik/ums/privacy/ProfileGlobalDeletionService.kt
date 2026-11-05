package com.vik.ums.privacy

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.lifecycle.LifecyclePersistence
import com.vik.ums.notices.rearmNotices
import com.vik.ums.notices.suppressNotices
import com.vik.ums.profiles.AppProfilePolicy
import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfileFieldsPersistence
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.ProfileStateEvents
import com.vik.ums.profiles.Readiness
import com.vik.ums.profiles.lockedPolicy
import com.vik.ums.recovery.ErasureFeed
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * IAM-global deletion is an independent cause from app-only deletion. It never needs the
 * global Human Identity, never resets a subject's source checkpoint, and its authoritative
 * final fact erases without opening another local grace period.
 */
class ProfileGlobalDeletionService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val persistence = PrivacyPersistence(jdbc)
    private val lifecycle = LifecyclePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)
    private val erasureJournal = ErasureFeed(jdbc)

    fun applyPending(authority: TrustedProviderAuthority, fact: GlobalPendingFact): GlobalDeletionResult =
        transactions.execute {
            requireApp(authority, fact.scope)
            lockedPolicy(jdbc, mapper, fact.scope.application())
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            val current = bindings.binding(fact.scope)
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            if (current.lifecycle == Lifecycle.ERASED ||
                fact.sourceRevision <= persistence.globalSourceRevision(fact.scope)) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            val revision = Math.addExact(current.bindingRevision.toLong(), 1)
            if (!persistence.pendingTransition(fact.scope, fact.sourceRevision, revision,
                    current.bindingRevision.toLong())) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            persistence.replaceGlobalCause(fact.scope, fact.sourceRevision, fact.acceptedAt, fact.deadline)
            val after = bindings.binding(fact.scope)!!
            events.emit(after, "DELETION_REQUESTED", fact.eventId, fact.correlationId)
            // Restriction invalidates any exported copy immediately, whatever the deadline ordering.
            invalidateExportsFor(jdbc, fact.scope, clock.instant())
            // Only an obligation earlier than the retention deadline supersedes its notices.
            val closureDeadline = bindings.closureDeadline(fact.scope)
            if (closureDeadline == null || fact.deadline.isBefore(closureDeadline)) {
                suppressNotices(jdbc, mapper, environment, fact.scope, fact.correlationId, clock.instant())
            }
            GlobalDeletionResult(GlobalDeletionOutcome.APPLIED, after)
        }!!

    fun cancel(authority: TrustedProviderAuthority, fact: GlobalCancellationFact): GlobalDeletionResult =
        transactions.execute {
            requireApp(authority, fact.scope)
            val policy: AppProfilePolicy = lockedPolicy(jdbc, mapper, fact.scope.application())
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            val current = bindings.binding(fact.scope)
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            if (current.lifecycle == Lifecycle.ERASED ||
                fact.sourceRevision <= persistence.globalSourceRevision(fact.scope) ||
                current.deletionCauses.iamGlobal == null) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            val lifecycleName = when {
                current.deletionCauses.appOnly != null -> Lifecycle.DELETION_PENDING
                persistence.liveClosure(fact.scope) || persistence.legacyClosure(fact.scope) -> Lifecycle.CLOSED
                else -> Lifecycle.ACTIVE
            }
            val readiness = if (lifecycleName == Lifecycle.ACTIVE) readiness(fact.scope, policy)
            else Readiness.PROFILE_REQUIRED
            if (!persistence.cancelTransition(fact.scope, fact.sourceRevision, lifecycleName.name,
                    readiness.name, current.bindingRevision.toLong())) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            persistence.deleteGlobalCause(fact.scope)
            val after = bindings.binding(fact.scope)!!
            events.emit(after, "DELETION_CANCELLED", fact.eventId, fact.correlationId)
            if (lifecycleName == Lifecycle.CLOSED) {
                bindings.closureReference(fact.scope)?.let {
                    rearmNotices(jdbc, mapper, environment, fact.scope, it, fact.correlationId,
                        clock.instant().truncatedTo(ChronoUnit.MICROS))
                }
            }
            GlobalDeletionResult(GlobalDeletionOutcome.APPLIED, after)
        }!!

    fun finalize(authority: TrustedProviderAuthority, fact: GlobalFinalFact): GlobalDeletionResult =
        transactions.execute {
            requireApp(authority, fact.scope)
            lockedPolicy(jdbc, mapper, fact.scope.application())
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            val current = bindings.binding(fact.scope)
                ?: return@execute GlobalDeletionResult(GlobalDeletionOutcome.UNAVAILABLE)
            if (current.lifecycle == Lifecycle.ERASED ||
                fact.sourceRevision <= persistence.globalSourceRevision(fact.scope)) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            // An authoritative global final is never silently dropped while a provider asset may
            // still exist: it stays blocked until the content owner confirms cleanup (or the retry
            // budget is exhausted), then a redelivery can erase locally with the debt visible.
            val content = jdbc.query("""select b.avatar_reference, o.content_reference, o.content_state
                from ums_profile_bindings b
                left join ums_profile_erasure_obligations o
                  on o.retired_profile_reference = b.profile_reference
                where b.issuer_environment_ref = ? and b.app_reference = ? and b.subject_reference = ?""",
                { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
                fact.scope.issuerEnvironmentRef, fact.scope.appReference, fact.scope.subjectReference).single()
            val association = content.first
            // A debt recorded by an earlier blocked attempt survives an out-of-band clear.
            val contentReference = association ?: content.second
            val contentState = content.third
            if (association != null && contentState != "CONFIRMED" && contentState != "FAILED") {
                lifecycle.recordBlockedFinal(fact.scope, fact.eventId, current.profileReference!!,
                    current.generation!!.toLong(), fact.occurredAt, current.bindingRevision.toLong(),
                    clock.instant(), association)
                lifecycle.recordEvidence(fact.scope, "GLOBAL_FINAL_BLOCKED", current.bindingRevision.toLong(),
                    fact.occurredAt, null, clock.instant(), fact.eventId, fact.correlationId)
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.CONTENT_BLOCKED, current)
            }
            val revision = Math.addExact(current.bindingRevision.toLong(), 1)
            val superseded = mapper.writeValueAsString(listOfNotNull(
                current.deletionCauses.appOnly?.let {
                    mapOf("type" to "APP_ONLY", "reference" to it.requestReference.toString())
                }))
            if (!persistence.eraseGlobal(fact.scope, fact.sourceRevision, revision,
                    current.bindingRevision.toLong())) {
                return@execute GlobalDeletionResult(GlobalDeletionOutcome.STALE, current)
            }
            erasureJournal.record(fact.scope, current.profileReference!!, current.generation!!.toLong(),
                revision, clock.instant(), "IAM_GLOBAL", fact.eventId)
            deleteAllCauses(fact.scope)
            markPendingRequestsSuperseded(fact.scope)
            lifecycle.recordErasedObligation(fact.scope, "IAM_GLOBAL", fact.eventId, current.profileReference!!,
                current.generation!!.toLong(), fact.occurredAt, superseded, revision, clock.instant(),
                contentReference, contentState ?: if (contentReference != null) "PENDING" else "NONE")
            invalidateExportsFor(jdbc, fact.scope, clock.instant())
            suppressNotices(jdbc, mapper, environment, fact.scope, fact.correlationId, clock.instant())
            lifecycle.recordEvidence(fact.scope, "GLOBAL_ERASED", revision, fact.occurredAt, null,
                clock.instant(), fact.eventId, fact.correlationId)

            val after = bindings.binding(fact.scope)!!
            check(after.lifecycle == Lifecycle.ERASED && after.readiness == Readiness.PROFILE_REQUIRED)
            events.emit(after, "ERASED", fact.eventId, fact.correlationId)
            GlobalDeletionResult(GlobalDeletionOutcome.APPLIED, after)
        }!!

    private fun readiness(scope: ProfileScope, policy: AppProfilePolicy): Readiness {
        val present = fields.fields(scope).keys
        return if (policy.requiredFields.all { it in present }) Readiness.PROFILE_READY
        else Readiness.PROFILE_REQUIRED
    }

    private fun requireApp(authority: TrustedProviderAuthority, scope: ProfileScope) =
        require(authority.app == scope.application()) { "Provider authority is outside the profile application" }

    private fun deleteAllCauses(scope: ProfileScope) {
        jdbc.update("""delete from ums_profile_deletion_causes
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    private fun markPendingRequestsSuperseded(scope: ProfileScope) {
        jdbc.update("""update ums_profile_deletion_requests set status = 'SUPERSEDED',
            operation_revision = operation_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and status = 'PENDING'""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }
}
