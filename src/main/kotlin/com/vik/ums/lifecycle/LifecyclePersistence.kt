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
    fun closeActive(scope: ProfileScope, closedAt: Instant, retainUntil: Instant): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'CLOSED', readiness = 'PROFILE_REQUIRED',
            binding_revision = binding_revision + 1, closure_present = true,
            closure_at = ?, closure_retain_until = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and lifecycle = 'ACTIVE'""",
            Timestamp.from(closedAt), Timestamp.from(retainUntil),
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference) == 1

    /** Restriction already in force: record retention without changing lifecycle, revision, or readiness. */
    fun recordRetention(scope: ProfileScope, closedAt: Instant, retainUntil: Instant) {
        jdbc.update("""update ums_profile_bindings set closure_present = true, closure_at = ?, closure_retain_until = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            Timestamp.from(closedAt), Timestamp.from(retainUntil),
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    fun recordEvidence(
        scope: ProfileScope, action: String, bindingRevision: Long, effectiveAt: Instant,
        retainUntil: Instant?, occurredAt: Instant, eventId: UUID, correlationId: String,
    ) {
        jdbc.update("""insert into ums_profile_lifecycle_evidence
            (evidence_reference, issuer_environment_ref, app_reference, subject_reference, action,
             binding_revision, effective_at, retain_until, occurred_at, causation_event_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            action, bindingRevision, Timestamp.from(effectiveAt), retainUntil?.let(Timestamp::from),
            Timestamp.from(occurredAt), eventId, correlationId)
    }
}
