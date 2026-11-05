package com.vik.ums.content

import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Durable cleanup debt for an avatar a profile dropped by clearing or replacing it. The patch
 * transaction records the debt with the change; the cleanup worker confirms it with the owner.
 */
class ContentCleanupRequests(private val jdbc: JdbcTemplate, private val clock: Clock) {
    fun record(scope: ProfileScope, reference: String) {
        jdbc.update("""insert into ums_content_cleanup_requests
            (request_reference, issuer_environment_ref, app_reference, subject_reference,
             content_reference, content_state, content_attempts, requested_at)
            values (?, ?, ?, ?, ?, 'PENDING', 0, ?)""",
            UUID.randomUUID(), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            reference, Timestamp.from(clock.instant()))
    }
}
