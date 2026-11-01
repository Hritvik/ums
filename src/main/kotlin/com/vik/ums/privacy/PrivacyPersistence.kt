package com.vik.ums.privacy

import com.vik.ums.profiles.BindingState
import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class PrivacyPersistence(private val jdbc: JdbcTemplate) {
    fun request(scope: ProfileScope, reference: UUID): StoredDeletionRequest = findRequest(scope, reference)
        ?: throw IllegalStateException("Deletion request is unavailable")

    fun findRequest(scope: ProfileScope, reference: UUID): StoredDeletionRequest? = jdbc.query("""select request_reference,
        profile_reference, generation, operation_revision, status, accepted_at, deadline from ums_profile_deletion_requests
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and request_reference = ?""",
        { rs, _ -> StoredDeletionRequest(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java),
            rs.getLong(3).toString(), rs.getLong(4).toString(), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference).singleOrNull()

    fun receipt(authority: TrustedPrivacyAuthority, key: UUID, fingerprint: String, state: BindingState): PrivacyAccepted? =
        jdbc.query("""select input_fingerprint, operation_reference, operation_revision, profile_reference, generation
            from ums_profile_privacy_receipts where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and actor_reference = ? and operation = ? and idempotency_key = ?""", { rs, _ ->
            require(rs.getString(1) == fingerprint) { "Privacy idempotency key content changed" }
            check(rs.getObject(4, UUID::class.java) == state.profileReference && rs.getLong(5).toString() == state.generation) {
                "Privacy receipt belongs to a retired generation"
            }
            PrivacyAccepted(rs.getObject(2, UUID::class.java), rs.getLong(3).toString())
        }, authority.scope.issuerEnvironmentRef, authority.scope.appReference, authority.scope.subjectReference,
            authority.actorReference, authority.purpose.name, key).singleOrNull()

    fun accept(state: BindingState, reference: UUID, now: Instant, deadline: Instant) {
        val scope = state.scope
        jdbc.update("""insert into ums_profile_deletion_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference, profile_reference, generation,
            operation_revision, status, accepted_at, deadline) values (?, ?, ?, ?, ?, ?, 1, 'PENDING', ?, ?)""",
            reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, state.profileReference,
            state.generation!!.toLong(), Timestamp.from(now), Timestamp.from(deadline))
        jdbc.update("""insert into ums_profile_deletion_causes
            (issuer_environment_ref, app_reference, subject_reference, cause_type, request_reference, source_revision, accepted_at, deadline)
            values (?, ?, ?, 'APP_ONLY', ?, 1, ?, ?)""", scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            reference, Timestamp.from(now), Timestamp.from(deadline))
        jdbc.update("""update ums_profile_bindings set closure_present = closure_present or lifecycle = 'CLOSED',
            lifecycle = 'DELETION_PENDING', readiness = 'PROFILE_REQUIRED', binding_revision = binding_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    fun cancel(scope: ProfileScope, reference: UUID) {
        jdbc.update("""update ums_profile_deletion_requests set status = 'CANCELLED', operation_revision = operation_revision + 1
            where request_reference = ?""", reference)
        jdbc.update("""delete from ums_profile_deletion_causes where issuer_environment_ref = ? and app_reference = ?
            and subject_reference = ? and cause_type = 'APP_ONLY' and request_reference = ?""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference)
    }

    fun globalSourceRevision(scope: ProfileScope): Long = jdbc.queryForObject("""select global_source_revision
        from ums_profile_bindings where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
        Long::class.java, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)!!

    fun replaceGlobalCause(scope: ProfileScope, sourceRevision: Long, acceptedAt: Instant, deadline: Instant) {
        deleteGlobalCause(scope)
        jdbc.update("""insert into ums_profile_deletion_causes
            (issuer_environment_ref, app_reference, subject_reference, cause_type, request_reference,
             source_revision, accepted_at, deadline)
            values (?, ?, ?, 'IAM_GLOBAL', ?, ?, ?, ?)""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, UUID.randomUUID(),
            sourceRevision, Timestamp.from(acceptedAt), Timestamp.from(deadline))
    }

    fun deleteGlobalCause(scope: ProfileScope) {
        jdbc.update("""delete from ums_profile_deletion_causes where issuer_environment_ref = ?
            and app_reference = ? and subject_reference = ? and cause_type = 'IAM_GLOBAL'""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    /** Returns false when the fact is not newer than the applied source revision. */
    fun pendingTransition(scope: ProfileScope, sourceRevision: Long, revision: Long,
                          expectedRevision: Long): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'DELETION_PENDING', readiness = 'PROFILE_REQUIRED',
            binding_revision = ?, global_source_revision = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and binding_revision = ? and lifecycle <> 'ERASED' and global_source_revision < ?""",
            revision, sourceRevision, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            expectedRevision, sourceRevision) == 1

    fun cancelTransition(scope: ProfileScope, sourceRevision: Long, lifecycle: String, readiness: String,
                         expectedRevision: Long): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = ?, readiness = ?,
            binding_revision = binding_revision + 1, global_source_revision = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and binding_revision = ? and lifecycle <> 'ERASED' and global_source_revision < ?""",
            lifecycle, readiness, sourceRevision, scope.issuerEnvironmentRef, scope.appReference,
            scope.subjectReference, expectedRevision, sourceRevision) == 1

    fun eraseGlobal(scope: ProfileScope, sourceRevision: Long, revision: Long, expectedRevision: Long): Boolean =
        jdbc.update("""update ums_profile_bindings set lifecycle = 'ERASED', readiness = 'PROFILE_REQUIRED',
            profile_reference = null, generation = null, profile_revision = null,
            closure_present = false, closure_reference = null, closure_at = null, closure_retain_until = null,
            display_name = null, avatar_reference = null, display_locale = null, display_timezone = null,
            binding_revision = ?, global_source_revision = ?
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and binding_revision = ? and lifecycle <> 'ERASED' and global_source_revision < ?""",
            revision, sourceRevision, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            expectedRevision, sourceRevision) == 1

    fun completedFence(scope: ProfileScope, request: StoredDeletionRequest, state: BindingState): Boolean {
        if (request.status != "LOCAL_COMPLETED" || state.lifecycle != com.vik.ums.profiles.Lifecycle.ERASED) return false
        return jdbc.query("""select f.retired_profile_reference, f.retired_generation, f.erased_binding_revision,
            b.completed_deletion_request_reference from ums_profile_erasure_obligations f join ums_profile_bindings b
            using (issuer_environment_ref, app_reference, subject_reference) where f.issuer_environment_ref=?
            and f.app_reference=? and f.subject_reference=? and f.obligation_kind = 'APP_DELETION'
            and f.cause_reference=?""", { rs, _ ->
            rs.getObject(1, UUID::class.java) == request.profileReference && rs.getLong(2).toString() == request.generation &&
                rs.getLong(3).toString() == state.bindingRevision && rs.getObject(4, UUID::class.java) == request.reference
        }, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, request.reference).singleOrNull() == true
    }

    /** A closure that still has a live retention deadline. The historical flag is sticky across reactivation. */
    fun liveClosure(scope: ProfileScope): Boolean = jdbc.queryForObject("""select closure_retain_until is not null
        from ums_profile_bindings where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
        Boolean::class.java, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)!!

    fun transition(scope: ProfileScope, lifecycle: String, readiness: String) {
        jdbc.update("""update ums_profile_bindings set lifecycle = ?, readiness = ?, binding_revision = binding_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""", lifecycle, readiness,
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    fun record(authority: TrustedPrivacyAuthority, key: UUID, fingerprint: String, state: BindingState,
               result: PrivacyAccepted, now: Instant, correlationId: String) {
        jdbc.update("""insert into ums_profile_privacy_receipts
            (issuer_environment_ref, app_reference, subject_reference, actor_reference, operation, idempotency_key,
            input_fingerprint, operation_reference, operation_revision, profile_reference, generation, accepted_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", authority.scope.issuerEnvironmentRef, authority.scope.appReference,
            authority.scope.subjectReference, authority.actorReference, authority.purpose.name, key, fingerprint,
            result.operationReference, result.operationRevision.toLong(), state.profileReference, state.generation!!.toLong(), Timestamp.from(now))
        jdbc.update("""insert into ums_profile_privacy_audit
            (audit_reference, operation_reference, actor_reference, action, binding_revision, occurred_at, correlation_id) values (?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), result.operationReference, authority.actorReference, authority.purpose.name,
            state.bindingRevision.toLong(), Timestamp.from(now), correlationId)
    }
}
