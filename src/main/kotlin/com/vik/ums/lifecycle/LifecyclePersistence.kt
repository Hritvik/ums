package com.vik.ums.lifecycle

import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Recorded closure evidence. The deadline is absolute, so retries never move it. */
internal data class RecordedClosure(
    val closedAt: Instant,
    val retainUntil: Instant?,
    val bindingRevision: Long,
)

internal class LifecyclePersistence(private val jdbc: JdbcTemplate) {
    fun closure(scope: ProfileScope): RecordedClosure? = jdbc.query("""select closure_at, closure_retain_until,
        binding_revision from ums_profile_bindings
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and closure_at is not null""",
        { rs, _ -> RecordedClosure(rs.getTimestamp(1).toInstant(),
            rs.getTimestamp(2)?.toInstant(), rs.getLong(3)) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()

    /** Returns false when the profile was not ACTIVE, so the caller never overwrites another restriction. */
    fun closeActive(scope: ProfileScope, closedAt: Instant, retainUntil: Instant, closureReference: UUID): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED',
            binding_revision = binding_revision + 1, closure_present = true, closure_reference = ?,
            closure_at = ?, closure_retain_until = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and lifecycle = 'ACTIVE'""",
            closureReference, Timestamp.from(closedAt), Timestamp.from(retainUntil),
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference) == 1

    /** Reopens a closed profile only; retention is cleared but closure history is retained. */
    fun reactivate(scope: ProfileScope, readiness: String): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'ACTIVE', readiness = ?,
            binding_revision = binding_revision + 1, closure_present = false,
            closure_at = null, closure_retain_until = null, closure_reference = null
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and lifecycle = 'CLOSED'""",
            readiness, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference) == 1

    /** Restriction already in force: record retention without changing lifecycle, revision, or readiness. */
    fun recordRetention(scope: ProfileScope, closedAt: Instant, retainUntil: Instant, closureReference: UUID) {
        jdbc.update("""update ums_profile_bindings set closure_present = true, closure_reference = ?,
            closure_at = ?, closure_retain_until = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            closureReference, Timestamp.from(closedAt), Timestamp.from(retainUntil),
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    /**
     * Erases a closed generation only. Field clearing matches the deletion finalizer so a
     * retained profile leaves the same tombstone shape regardless of which obligation expired.
     */
    fun expireErased(scope: ProfileScope, revision: Long, expectedRevision: Long): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED', readiness = 'PROFILE_REQUIRED',
            profile_reference = null, generation = null, profile_revision = null,
            closure_present = false, closure_reference = null, closure_at = null, closure_retain_until = null,
            display_name = null, avatar_reference = null, display_locale = null, display_timezone = null,
            binding_revision = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and binding_revision = ? and lifecycle in ('CLOSED', 'DELETION_PENDING')""",
            revision, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            expectedRevision) == 1

    /** Clears superseded deletion causes so an erased binding carries no active obligation. */
    fun clearCauses(scope: ProfileScope) {
        jdbc.update("""delete from ums_profile_deletion_causes
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    fun recordErasedObligation(
        scope: ProfileScope, obligationKind: String, causeReference: UUID?, retiredProfileReference: UUID,
        retiredGeneration: Long, obligationAt: Instant, supersededCauses: String,
        erasedRevision: Long, erasedAt: Instant, contentReference: String? = null,
        contentState: String = if (contentReference != null) "PENDING" else "NONE",
    ) {
        // A global final that was already blocked wrote its fence first; completing it updates
        // that same row instead of inserting a second obligation for the retired profile.
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             cause_reference, retired_profile_reference, retired_generation, obligation_at, superseded_causes,
             erased_binding_revision, erased_at, content_obligation, content_state, content_reference)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            on conflict (retired_profile_reference) do update set
                obligation_kind = excluded.obligation_kind,
                cause_reference = excluded.cause_reference,
                obligation_at = excluded.obligation_at,
                superseded_causes = excluded.superseded_causes,
                erased_binding_revision = excluded.erased_binding_revision,
                erased_at = excluded.erased_at,
                content_obligation = excluded.content_obligation,
                content_state = excluded.content_state,
                content_reference = excluded.content_reference,
                content_attempts = case when excluded.content_state = 'PENDING' then 0
                    else ums_profile_erasure_obligations.content_attempts end,
                content_failure_class = case when excluded.content_state = 'PENDING' then null
                    else ums_profile_erasure_obligations.content_failure_class end""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            obligationKind, causeReference, retiredProfileReference, retiredGeneration,
            Timestamp.from(obligationAt), supersededCauses, erasedRevision, Timestamp.from(erasedAt),
            contentReference != null, contentState, contentReference)
    }

    /**
     * Records a global final that is blocked by a live avatar association. Cleanup confirms or
     * exhausts against this fence row, and a redelivered final can then erase.
     */
    fun recordBlockedFinal(
        scope: ProfileScope, causeReference: UUID, retiredProfileReference: UUID, retiredGeneration: Long,
        obligationAt: Instant, revision: Long, blockedAt: Instant, contentReference: String,
    ) {
        jdbc.update("""insert into ums_profile_erasure_obligations
            (fence_reference, issuer_environment_ref, app_reference, subject_reference, obligation_kind,
             cause_reference, retired_profile_reference, retired_generation, obligation_at, superseded_causes,
             erased_binding_revision, erased_at, content_obligation, content_state, content_reference)
            values (?, ?, ?, ?, 'IAM_GLOBAL_BLOCKED', ?, ?, ?, ?, '[]'::jsonb, ?, ?, true, 'PENDING', ?)
            on conflict (retired_profile_reference) do update set
                content_reference = excluded.content_reference,
                content_state = 'PENDING',
                -- A redelivery for the same asset must not restart the retry budget.
                content_attempts = case when ums_profile_erasure_obligations.content_reference
                    is distinct from excluded.content_reference then 0
                    else ums_profile_erasure_obligations.content_attempts end,
                content_failure_class = case when ums_profile_erasure_obligations.content_reference
                    is distinct from excluded.content_reference then null
                    else ums_profile_erasure_obligations.content_failure_class end""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            causeReference, retiredProfileReference, retiredGeneration, Timestamp.from(obligationAt),
            revision, Timestamp.from(blockedAt), contentReference)
    }

    fun recordEvidence(
        scope: ProfileScope, action: String, bindingRevision: Long, effectiveAt: Instant,
        retainUntil: Instant?, occurredAt: Instant, eventId: UUID, correlationId: String,
    ) {
        jdbc.update("""insert into ums_profile_lifecycle_evidence
            (evidence_reference, issuer_environment_ref, app_reference, subject_reference, action,
             binding_revision, effective_at, retain_until, occurred_at, causation_event_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (issuer_environment_ref, app_reference, subject_reference, action, causation_event_id)
            do nothing""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            action, bindingRevision, Timestamp.from(effectiveAt), retainUntil?.let(Timestamp::from),
            Timestamp.from(occurredAt), eventId, correlationId)
    }
}
