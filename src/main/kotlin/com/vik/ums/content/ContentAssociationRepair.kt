package com.vik.ums.content

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.ProfileField
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.ProfileStateEvents
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Repairs an association the content owner says is invalid. Reads never call the provider, so a
 * validated worker sweep is where `REVOKED` and an ownership mismatch clear the pointer: both drop
 * the association and show the neutral avatar, and a mismatch never revokes the other scope's asset.
 * `UNAVAILABLE` keeps the association. The oldest check rotates first, so one pass cannot starve a
 * later profile.
 */
class ContentAssociationRepair(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val authority: ContentAuthority,
    private val clock: Clock,
    private val recheckAfter: Duration = Duration.ofHours(1),
) {
    private val bindings = ProfilePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun run(limit: Int = 50): Int {
        val checkedBefore = Timestamp.from(clock.instant().minus(recheckAfter))
        val candidates = jdbc.query("""select issuer_environment_ref, app_reference, subject_reference,
                avatar_reference, binding_revision
            from ums_profile_bindings
            where avatar_reference is not null and lifecycle = 'ACTIVE'
              and (content_checked_at is null or content_checked_at <= ?)
            order by content_checked_at nulls first, binding_revision, subject_reference limit ?""",
            { rs, _ -> Candidate(
                ProfileScope(rs.getObject(1, java.util.UUID::class.java),
                    rs.getObject(2, java.util.UUID::class.java), rs.getObject(3, java.util.UUID::class.java)),
                rs.getString(4), rs.getLong(5)) }, checkedBefore, limit)
        var repaired = 0
        for (candidate in candidates) {
            // Marking the check is independent of the verdict, so a still-valid asset is not
            // re-queried on every pass and the next pass moves on.
            jdbc.update("""update ums_profile_bindings set content_checked_at = ?
                where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
                and avatar_reference = ?""", Timestamp.from(clock.instant()), candidate.scope.issuerEnvironmentRef,
                candidate.scope.appReference, candidate.scope.subjectReference, candidate.reference)
            when (authority.check(candidate.reference, candidate.scope)) {
                ContentVerdict.REVOKED, ContentVerdict.OWNER_MISMATCH -> if (clear(candidate)) repaired++
                ContentVerdict.VALID, ContentVerdict.UNAVAILABLE -> Unit
            }
        }
        return repaired
    }

    private fun clear(candidate: Candidate): Boolean = transactions.execute {
        val avatarRequired = lockedPolicy(jdbc, mapper, candidate.scope.application())
            ?.requiredFields?.contains(ProfileField.AVATAR_REFERENCE) == true
        val updated = jdbc.update("""update ums_profile_bindings set avatar_reference = null,
            readiness = case when ? then 'PROFILE_REQUIRED' else readiness end,
            profile_revision = profile_revision + 1, binding_revision = binding_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
              and binding_revision = ? and avatar_reference = ? and lifecycle = 'ACTIVE'""",
            avatarRequired, candidate.scope.issuerEnvironmentRef, candidate.scope.appReference,
            candidate.scope.subjectReference, candidate.bindingRevision, candidate.reference)
        if (updated == 1) {
            // One canonical field change, exactly like an explicit patch; the neutral avatar shows.
            events.emit(bindings.binding(candidate.scope)!!, "FIELDS_CHANGED", UUID.randomUUID(), "content_repair")
        }
        updated == 1
    } ?: false

    private data class Candidate(val scope: ProfileScope, val reference: String, val bindingRevision: Long)
}
