package com.vik.ums.content

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.ProfileStateEvents
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * Repairs an association the content owner says is invalid. Reads never call the provider, so a
 * validated worker sweep is where `REVOKED` and an ownership mismatch clear the pointer: both drop
 * the association and show the neutral avatar, and a mismatch never revokes the other scope's asset.
 * `UNAVAILABLE` keeps the association.
 */
class ContentAssociationRepair(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val authority: ContentAuthority,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun run(limit: Int = 50): Int {
        val candidates = jdbc.query("""select issuer_environment_ref, app_reference, subject_reference,
                avatar_reference, binding_revision
            from ums_profile_bindings
            where avatar_reference is not null and lifecycle = 'ACTIVE'
            order by binding_revision, subject_reference limit ?""",
            { rs, _ -> Candidate(
                ProfileScope(rs.getObject(1, java.util.UUID::class.java),
                    rs.getObject(2, java.util.UUID::class.java), rs.getObject(3, java.util.UUID::class.java)),
                rs.getString(4), rs.getLong(5)) }, limit)
        var repaired = 0
        for (candidate in candidates) {
            when (authority.check(candidate.reference, candidate.scope)) {
                ContentVerdict.REVOKED, ContentVerdict.OWNER_MISMATCH -> if (clear(candidate)) repaired++
                ContentVerdict.VALID, ContentVerdict.UNAVAILABLE -> Unit
            }
        }
        return repaired
    }

    private fun clear(candidate: Candidate): Boolean = transactions.execute {
        val updated = jdbc.update("""update ums_profile_bindings set avatar_reference = null,
            profile_revision = profile_revision + 1, binding_revision = binding_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
              and binding_revision = ? and avatar_reference = ? and lifecycle = 'ACTIVE'""",
            candidate.scope.issuerEnvironmentRef, candidate.scope.appReference,
            candidate.scope.subjectReference, candidate.bindingRevision, candidate.reference)
        if (updated == 1) {
            // One canonical field change, exactly like an explicit patch; the neutral avatar shows.
            events.emit(bindings.binding(candidate.scope)!!, "FIELDS_CHANGED", UUID.randomUUID(), "content-repair")
        }
        updated == 1
    } ?: false

    private data class Candidate(val scope: ProfileScope, val reference: String, val bindingRevision: Long)
}
