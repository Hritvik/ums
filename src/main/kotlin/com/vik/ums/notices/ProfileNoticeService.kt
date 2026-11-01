package com.vik.ums.notices

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.Lifecycle
import com.vik.ums.profiles.ProfilePersistence
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Publishes minimized, app-routed retention notices. UMS holds no contact details,
 * chooses no channel or wording, and never claims a message was delivered: the
 * product or notice owner turns an intent into an actual notice.
 */
class ProfileNoticeService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val clock: Clock,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val persistence = NoticePersistence(jdbc)
    init { require(environment.matches(Regex("[a-z0-9-]{1,32}"))) }

    fun schedule(authority: TrustedNoticeAuthority, scope: ProfileScope, correlationId: String): List<NoticeResult> {
        require(authority.app == scope.application()) { "Notice authority is outside the profile application" }
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        return transactions.execute {
            // The policy row is the shared application lock. Adoption is not required:
            // a notice obligation was accepted while the app was adopted.
            lockedPolicy(jdbc, mapper, scope.application())
                ?: return@execute NoticeClass.entries.map { NoticeResult(NoticeOutcome.UNAVAILABLE, it) }
            val state = bindings.binding(scope)
                ?: return@execute NoticeClass.entries.map { NoticeResult(NoticeOutcome.UNAVAILABLE, it) }
            val deadline = bindings.closureDeadline(scope)
            val closureReference = bindings.closureReference(scope)
            if (state.lifecycle != Lifecycle.CLOSED || deadline == null || closureReference == null) {
                return@execute NoticeClass.entries.map { NoticeResult(NoticeOutcome.NOT_CLOSED, it) }
            }
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            NoticeClass.entries.map { noticeClass ->
                scheduleOne(scope, closureReference, noticeClass, deadline, now, correlationId)
            }
        }!!
    }

    /** One intent per class and closure instance, so a re-closed profile gets fresh notices. */
    private fun scheduleOne(
        scope: ProfileScope, closureReference: UUID, noticeClass: NoticeClass, deadline: Instant,
        now: Instant, correlationId: String,
    ): NoticeResult {
        val dedupe = dedupeIdentity(scope, noticeClass, closureReference)
        persistence.findByDedupe(scope, dedupe)?.let { existing ->
            val outcome = if (existing.state == NoticeState.WINDOW_PASSED) NoticeOutcome.WINDOW_PASSED
            else NoticeOutcome.ALREADY_QUEUED
            return NoticeResult(outcome, noticeClass, existing.reference, deadline)
        }
        // A closure observed after its window start never shifts the window: the notice is
        // recorded as passed so operators can see the missed obligation rather than silence.
        if (now.isAfter(deadline.minusSeconds(noticeClass.windowDays * 86_400))) {
            val reference = UUID.randomUUID()
            persistence.insert(scope, reference, noticeClass, deadline, dedupe, NoticeState.WINDOW_PASSED, now, closureReference)
            appendNoticeFact(jdbc, mapper, environment, scope, reference, noticeClass, deadline, dedupe,
                NoticeState.WINDOW_PASSED, 1, correlationId, now)
            return NoticeResult(NoticeOutcome.WINDOW_PASSED, noticeClass, reference, deadline)
        }

        val reference = UUID.randomUUID()
        persistence.insert(scope, reference, noticeClass, deadline, dedupe, NoticeState.QUEUED, now, closureReference)
        appendNoticeFact(jdbc, mapper, environment, scope, reference, noticeClass, deadline, dedupe,
            NoticeState.QUEUED, 1, correlationId, now)
        return NoticeResult(NoticeOutcome.QUEUED, noticeClass, reference, deadline)
    }

    fun markHandedOff(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID, correlationId: String) {
        val stored = requireTransition(authority, scope, reference)
        if (stored.state == NoticeState.HANDED_OFF) return
        require(stored.state in setOf(NoticeState.QUEUED, NoticeState.FAILED)) {
            "Notice transition is not allowed from ${stored.state}"
        }
        apply(scope, stored, NoticeState.HANDED_OFF, "handed_off_at", correlationId)
    }

    /** A late delivery after suppression is recorded, not turned into a permanent adapter error. */
    fun acknowledge(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID, correlationId: String) {
        val stored = requireTransition(authority, scope, reference)
        if (stored.state == NoticeState.ACKNOWLEDGED) return
        require(stored.state in setOf(NoticeState.HANDED_OFF, NoticeState.SUPPRESSED)) {
            "Notice transition is not allowed from ${stored.state}"
        }
        val late = stored.state == NoticeState.SUPPRESSED
        apply(scope, stored, NoticeState.ACKNOWLEDGED, "acknowledged_at", correlationId, lateAfterSuppression = late)
    }

    fun fail(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID,
             failureClass: String, correlationId: String) {
        require(failureClass in SAFE_FAILURE_CLASSES) { "Failure class is not a known safe code" }
        val stored = requireTransition(authority, scope, reference)
        if (stored.state == NoticeState.FAILED) return
        require(stored.state in setOf(NoticeState.QUEUED, NoticeState.HANDED_OFF)) {
            "Notice transition is not allowed from ${stored.state}"
        }
        apply(scope, stored, NoticeState.FAILED, "failed_at", correlationId, failureClass = failureClass)
    }

    private fun requireTransition(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID): StoredNotice {
        require(authority.app == scope.application()) { "Notice authority is outside the profile application" }
        val stored = persistence.find(scope, reference) ?: throw IllegalStateException("Notice intent is unavailable")
        return stored
    }

    private fun apply(
        scope: ProfileScope, stored: StoredNotice, to: NoticeState, column: String, correlationId: String,
        failureClass: String? = null, lateAfterSuppression: Boolean = false,
    ) {
        transactions.executeWithoutResult {
            lockedPolicy(jdbc, mapper, scope.application()) ?: throw IllegalStateException("Application is not registered")
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val revision = persistence.transition(scope, stored.reference, setOf(stored.state), to, column, now,
                failureClass, lateAfterSuppression) ?: throw IllegalStateException("Notice state changed concurrently")
            appendNoticeFact(jdbc, mapper, environment, scope, stored.reference, stored.noticeClass, stored.deadline,
                stored.dedupeIdentity, to, revision, correlationId, now)
        }
    }

    /** Dedupe identity is bound to the closure instance, not only to the class and deadline. */
    private fun dedupeIdentity(scope: ProfileScope, noticeClass: NoticeClass, closureReference: UUID): String =
        MessageDigest.getInstance("SHA-256").digest(
            mapper.writeValueAsBytes(listOf(scope.issuerEnvironmentRef.toString(), scope.appReference.toString(),
                scope.subjectReference.toString(), noticeClass.name, closureReference.toString())))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SAFE_FAILURE_CLASSES = setOf(
            "ADAPTER_UNAVAILABLE", "INVALID_RECIPIENT", "POLICY_REJECTED", "QUOTA_EXCEEDED", "UNKNOWN",
        )
    }
}
