package com.vik.ums.privacy

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.notices.suppressNotices
import com.vik.ums.profiles.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Resolved local maintenance authority only. No transport, scheduler or production proof is wired. */
data class TrustedMaintenanceAuthority(val app: AppScope, val actorReference: UUID, val purpose: MaintenancePurpose)
enum class MaintenancePurpose { FINALIZE_APP_DELETION }

/** Local app-only finalization. Does not assert content cleanup, recovery durability or downstream completion. */
class ProfileFinalizationService(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate, private val mapper: ObjectMapper,
    private val environment: String, private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val persistence = PrivacyPersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun finalize(authority: TrustedMaintenanceAuthority, scope: ProfileScope, reference: UUID,
                 correlationId: String): DeletionRequestStatus {
        require(authority.app == scope.application() && authority.purpose == MaintenancePurpose.FINALIZE_APP_DELETION)
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        return transactions.execute {
            requireFinalization(lockedPolicy(jdbc, mapper, authority.app) != null, FinalizationBlockReason.APP_UNAVAILABLE)
            val now = clock.instant()
            val request = persistence.findRequest(scope, reference)
                ?: throw FinalizationBlockedException(FinalizationBlockReason.REQUEST_UNAVAILABLE)
            val before = bindings.binding(scope) ?: throw FinalizationBlockedException(FinalizationBlockReason.PROFILE_UNAVAILABLE)
            if (request.status == "SUPERSEDED") throw FinalizationBlockedException(FinalizationBlockReason.SUPERSEDED)
            if (request.status == "LOCAL_COMPLETED") {
                check(persistence.completedFence(scope, request, before)) { "Completed deletion fence does not match current binding" }
                return@execute completed(request, before)
            }
            if (request.status == "CANCELLED") throw FinalizationBlockedException(FinalizationBlockReason.CANCELLED)
            check(request.status == "PENDING") { "Unexpected deletion request state" }
            requireFinalization(request.profileReference == before.profileReference && request.generation == before.generation, FinalizationBlockReason.GENERATION_MISMATCH)
            check(before.lifecycle == Lifecycle.DELETION_PENDING) { "Pending deletion has inconsistent binding lifecycle" }
            val cause = before.deletionCauses.appOnly
            check(cause?.requestReference == reference && Instant.parse(cause.deadline) == request.deadline &&
                Instant.parse(cause.acceptedAt) == request.acceptedAt && cause.sourceRevision == request.revision) { "App-only cause does not match request" }
            requireFinalization(!now.isBefore(request.deadline), FinalizationBlockReason.NOT_DUE)
            requireFinalization(jdbc.queryForObject("""select avatar_reference is null from ums_profile_bindings
                where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""", Boolean::class.java,
                scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference) == true, FinalizationBlockReason.CONTENT_AUTHORITY_UNAVAILABLE)
            erase(scope, request, before)
            suppressNotices(jdbc, mapper, environment, scope, correlationId, now)
            val after = bindings.binding(scope)!!
            audit(authority, request, after, now, correlationId)
            events.emit(after, "ERASED", reference, correlationId)
            completed(persistence.request(scope, reference), after)
        }!!
    }

    private fun erase(scope: ProfileScope, request: StoredDeletionRequest, before: BindingState) {
        val revision = Math.addExact(before.bindingRevision.toLong(), 1)
        val superseded = mapper.writeValueAsString(listOfNotNull(
            before.deletionCauses.iamGlobal?.let {
                mapOf("type" to "IAM_GLOBAL", "reference" to it.requestReference.toString())
            }))
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             cause_reference, retired_profile_reference, retired_generation, obligation_at, superseded_causes,
             erased_binding_revision, erased_at)
            values (?, ?, ?, ?, 'APP_DELETION', ?, ?, ?, ?, ?::jsonb, ?, ?)""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            request.reference, request.profileReference, request.generation.toLong(), Timestamp.from(request.acceptedAt),
            superseded, revision, Timestamp.from(clock.instant()))
        jdbc.update("""delete from ums_profile_deletion_causes where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        jdbc.update("""update ums_profile_bindings set lifecycle='ERASED', readiness='PROFILE_REQUIRED', profile_reference=null,
            generation=null, profile_revision=null, closure_present=false, closure_reference=null, closure_at=null, closure_retain_until=null,
            display_name=null, avatar_reference=null, display_locale=null,
            display_timezone=null, binding_revision=?, completed_deletion_request_reference=?
            where issuer_environment_ref=? and app_reference=? and subject_reference=?""",
            revision, request.reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
        jdbc.update("""update ums_profile_deletion_requests set status='LOCAL_COMPLETED', operation_revision=operation_revision+1
            where request_reference=?""", request.reference)
    }
    private fun audit(authority: TrustedMaintenanceAuthority, request: StoredDeletionRequest,
                      state: BindingState, now: Instant, correlationId: String) {
        jdbc.update("""insert into ums_profile_privacy_audit
            (audit_reference, operation_reference, actor_reference, action, binding_revision, occurred_at, correlation_id)
            values (?, ?, ?, 'FINALIZE_APP_DELETION', ?, ?, ?)""", UUID.randomUUID(), request.reference,
            authority.actorReference, state.bindingRevision.toLong(), Timestamp.from(now), correlationId)
    }
    private fun completed(request: StoredDeletionRequest, state: BindingState): DeletionRequestStatus =
        DeletionRequestStatus(request.reference, request.revision, state.bindingRevision, "LOCAL_COMPLETED",
            request.acceptedAt.toString(), request.deadline.toString(), false)
}
