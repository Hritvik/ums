package com.vik.ums.operations

import com.vik.ums.notices.ProfileNoticeService
import com.vik.ums.notices.TrustedNoticeAuthority
import com.vik.ums.privacy.DeletionBatchCursor
import com.vik.ums.privacy.DueDeletionBatchService
import com.vik.ums.privacy.MaintenancePurpose
import com.vik.ums.privacy.TrustedMaintenanceAuthority
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class MaintenanceResult(
    val skipped: Boolean,
    val finalized: Int = 0,
    val blocked: Int = 0,
    val retryable: Int = 0,
    val noticesScheduled: Int = 0,
)

/**
 * Drives the due-deletion batches and the notice obligations on a schedule. A lease makes the
 * pass single-flight; the underlying transitions are idempotent, so even a stolen pass cannot
 * duplicate an erasure or a notice.
 */
class MaintenanceWorker(
    private val jdbc: JdbcTemplate,
    private val lease: WorkerLease,
    private val batches: DueDeletionBatchService,
    private val notices: ProfileNoticeService,
    private val clock: Clock,
    private val batchLimit: Int = 50,
    private val maxPages: Int = 20,
    private val noticeLimit: Int = 100,
    private val maxFinalizations: Int = 200,
) {
    init {
        require(batchLimit in 1..100) { "Batch limit must be between 1 and 100" }
        require(maxPages > 0 && noticeLimit > 0 && maxFinalizations > 0)
    }

    fun run(): MaintenanceResult {
        val holder = lease.tryAcquire(WORKER, Duration.ofMinutes(10)) ?: return MaintenanceResult(skipped = true)
        return try {
            val finalized = finalizeDue(holder)
            val scheduled = scheduleNotices()
            MaintenanceResult(false, finalized.first, finalized.second, finalized.third, scheduled)
        } finally {
            runCatching { lease.release(WORKER, holder) }
        }
    }

    private fun finalizeDue(holder: UUID): Triple<Int, Int, Int> {
        var finalized = 0
        var blocked = 0
        var retryable = 0
        val apps = applications()
        val cursors = mutableMapOf<AppScope, DeletionBatchCursor?>()
        val pages = mutableMapOf<AppScope, Int>()
        var leaseHeld = true
        // Round-robin one page per application, so a permanently blocked app cannot consume the
        // whole pass while others wait.
        while (leaseHeld && apps.any { (pages[it] ?: 0) < maxPages } && finalized < maxFinalizations) {
            var progressed = false
            for (app in apps) {
                if ((pages[app] ?: 0) >= maxPages) continue
                val result = batches.run(
                    TrustedMaintenanceAuthority(app, UUID.randomUUID(), MaintenancePurpose.FINALIZE_APP_DELETION),
                    batchLimit, cursors[app], "maintenance-worker")
                result.results.forEach {
                    when (it.outcome) {
                        com.vik.ums.privacy.DeletionBatchOutcome.LOCAL_COMPLETED -> finalized++
                        com.vik.ums.privacy.DeletionBatchOutcome.BLOCKED -> blocked++
                        com.vik.ums.privacy.DeletionBatchOutcome.RETRYABLE -> retryable++
                    }
                }
                pages[app] = (pages[app] ?: 0) + 1
                cursors[app] = result.nextCursor
                if (result.nextCursor == null) pages[app] = maxPages
                progressed = true
                if (finalized >= maxFinalizations) break
                leaseHeld = lease.renew(WORKER, holder, Duration.ofMinutes(10))
                if (!leaseHeld) break
            }
            if (!progressed) break
        }
        if (!leaseHeld) log.warn("Maintenance worker lost its lease and stopped the pass")
        if (finalized + blocked + retryable > 0) {
            log.info("Maintenance pass finalized {}, blocked {}, retryable {}", finalized, blocked, retryable)
        }
        return Triple(finalized, blocked, retryable)
    }

    /** Most overdue application first, so one busy app cannot starve the others across passes. */
    private fun applications(): List<AppScope> =
        jdbc.query("""select p.issuer_environment_ref, p.app_reference
            from ums_profile_policies p
            left join lateral (
                select min(r.deadline) as oldest
                from ums_profile_deletion_requests r
                where r.issuer_environment_ref = p.issuer_environment_ref
                  and r.app_reference = p.app_reference
                  and r.status = 'PENDING' and r.deadline <= ?
            ) due on true
            order by due.oldest nulls last, p.app_reference""",
            { rs, _ -> AppScope(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) },
            java.sql.Timestamp.from(clock.instant()))

    /** A closed profile without an intent for its current closure instance gets one. */
    private fun scheduleNotices(): Int {
        val due = jdbc.query("""select b.issuer_environment_ref, b.app_reference, b.subject_reference
            from ums_profile_bindings b
            where b.lifecycle = 'CLOSED' and b.closure_reference is not null
              and not exists (select 1 from ums_profile_notice_intents n
                  where n.issuer_environment_ref = b.issuer_environment_ref
                    and n.app_reference = b.app_reference
                    and n.subject_reference = b.subject_reference
                    and n.closure_reference = b.closure_reference)
            order by b.closure_at, b.subject_reference limit ?""",
            { rs, _ -> ProfileScope(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java),
                rs.getObject(3, UUID::class.java)) }, noticeLimit)
        var scheduled = 0
        for (scope in due) {
            val results = try {
                notices.schedule(
                    TrustedNoticeAuthority(scope.application(), UUID.randomUUID()), scope, "maintenance-worker")
            } catch (unavailable: Exception) {
                // One failing scope must not stop the others; the next pass retries it.
                log.warn("Notice scheduling failed ({})", unavailable.javaClass.simpleName)
                continue
            }
            scheduled += results.count { it.outcome == com.vik.ums.notices.NoticeOutcome.QUEUED }
        }
        if (scheduled > 0) log.info("Maintenance pass queued {} retention notices", scheduled)
        return scheduled
    }

    private companion object {
        const val WORKER = "maintenance"
        val log = LoggerFactory.getLogger(MaintenanceWorker::class.java)
    }
}
