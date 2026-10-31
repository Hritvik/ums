package com.vik.ums.privacy

import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class DeletionBatchCursor(val app: AppScope, val cutoff: String, val afterDeadline: String, val afterRequestReference: UUID)
enum class DeletionBatchOutcome { LOCAL_COMPLETED, BLOCKED, RETRYABLE }
data class DeletionBatchItem(val requestReference: UUID, val outcome: DeletionBatchOutcome)
data class DeletionBatchResult(val app: AppScope, val cutoff: String, val results: List<DeletionBatchItem>, val nextCursor: DeletionBatchCursor?)

/** Explicit one-page local maintenance invocation. No scheduler, lease, durable retry or transport is wired. */
class DueDeletionBatchService(private val jdbc: JdbcTemplate, private val finalizer: ProfileFinalizationService, private val clock: Clock) {
    private data class Candidate(val scope: ProfileScope, val reference: UUID, val deadline: Instant)

    fun run(authority: TrustedMaintenanceAuthority, limit: Int, cursor: DeletionBatchCursor?, correlationId: String): DeletionBatchResult {
        require(authority.purpose == MaintenancePurpose.FINALIZE_APP_DELETION)
        require(limit in 1..100)
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(cursor == null || cursor.app == authority.app) { "Batch cursor application mismatch" }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Batch maintenance cannot run inside an existing transaction" }
        val now = clock.instant()
        val cutoff = cursor?.let { timestamp(it.cutoff) } ?: timestamp(now.truncatedTo(ChronoUnit.MICROS).toString())
        require(!cutoff.isAfter(now)) { "Batch cutoff is in the future" }
        val after = cursor?.let { timestamp(it.afterDeadline) }
        require(after == null || !after.isAfter(cutoff)) { "Cursor position exceeds cutoff" }
        val selected = candidates(authority.app, cutoff, cursor, after, limit + 1)
        val page = selected.take(limit)
        val results = page.map { candidate ->
            val outcome = try {
                finalizer.finalize(authority, candidate.scope, candidate.reference, correlationId)
                DeletionBatchOutcome.LOCAL_COMPLETED
            } catch (_: FinalizationBlockedException) {
                DeletionBatchOutcome.BLOCKED
            } catch (_: TransientDataAccessException) {
                DeletionBatchOutcome.RETRYABLE
            } catch (_: RecoverableDataAccessException) {
                DeletionBatchOutcome.RETRYABLE
            }
            DeletionBatchItem(candidate.reference, outcome)
        }
        val next = if (selected.size > limit) page.last().let {
            DeletionBatchCursor(authority.app, cutoff.toString(), it.deadline.toString(), it.reference)
        } else null
        return DeletionBatchResult(authority.app, cutoff.toString(), results, next)
    }

    private fun candidates(app: AppScope, cutoff: Instant, cursor: DeletionBatchCursor?, after: Instant?, limit: Int): List<Candidate> {
        val position = if (cursor == null) "" else "and (deadline, request_reference) > (?, ?)"
        val params = listOf(app.issuerEnvironmentRef, app.appReference, Timestamp.from(cutoff)) +
            (if (cursor == null) emptyList() else listOf(Timestamp.from(after!!), cursor.afterRequestReference)) + limit
        return jdbc.query("""select subject_reference, request_reference, deadline from ums_profile_deletion_requests
            where issuer_environment_ref=? and app_reference=? and status='PENDING' and deadline<=? $position
            order by deadline, request_reference limit ?""", { rs, _ ->
            Candidate(ProfileScope(app.issuerEnvironmentRef, app.appReference, rs.getObject(1, UUID::class.java)),
                rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant())
        }, *params.toTypedArray())
    }
    private fun timestamp(value: String): Instant {
        require(value.length <= 35 && !value.startsWith("0000") &&
            value.matches(Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z"""))) { "Invalid batch cursor timestamp" }
        val instant = runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("Invalid batch cursor timestamp") }
        require(value == instant.toString() && instant == instant.truncatedTo(ChronoUnit.MICROS)) { "Batch timestamp must be canonical UTC microseconds" }
        return instant
    }
}
