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
            if (state.lifecycle != Lifecycle.CLOSED) {
                return@execute NoticeClass.entries.map { NoticeResult(NoticeOutcome.NOT_CLOSED, it) }
            }
            val deadline = bindings.closureDeadline(scope)
                ?: return@execute NoticeClass.entries.map { NoticeResult(NoticeOutcome.NOT_CLOSED, it) }
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            NoticeClass.entries.map { noticeClass -> scheduleOne(scope, noticeClass, deadline, now, correlationId) }
        }!!
    }

    private fun scheduleOne(
        scope: ProfileScope, noticeClass: NoticeClass, deadline: Instant,
        now: Instant, correlationId: String,
    ): NoticeResult {
        val dedupe = dedupeIdentity(scope, noticeClass, deadline)
        persistence.findByDedupe(scope, dedupe)?.let {
            return NoticeResult(NoticeOutcome.ALREADY_QUEUED, noticeClass, it, deadline)
        }
        // A closure observed late never shifts the window; a past window is reported, not recreated.
        if (!now.isBefore(deadline.minusSeconds(noticeClass.windowDays * 86_400)))
            return NoticeResult(NoticeOutcome.WINDOW_PASSED, noticeClass, deadline = deadline)

        val reference = UUID.randomUUID()
        persistence.insert(scope, reference, noticeClass, deadline, dedupe, now)
        val payload = mapper.writeValueAsString(mapOf(
            "noticeReference" to reference, "schemaVersion" to "1", "type" to "ums.profile.notice-intent.v1",
            "producer" to "ums", "environment" to environment, "occurredAt" to now.toString(),
            "correlationId" to correlationId, "scope" to scope, "noticeClass" to noticeClass.name,
            "deadline" to deadline.toString(), "dedupeIdentity" to dedupe, "state" to "QUEUED",
        ))
        val streamId = "${scope.issuerEnvironmentRef}:${scope.appReference}:${scope.subjectReference}:notice"
        persistence.appendIntent(scope.appReference.toString(), streamId, reference, payload, now)
        return NoticeResult(NoticeOutcome.QUEUED, noticeClass, reference, deadline)
    }

    fun markHandedOff(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID) =
        transition(authority, scope, reference, setOf(NoticeState.QUEUED, NoticeState.FAILED),
            NoticeState.HANDED_OFF, "handed_off_at")

    fun acknowledge(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID) =
        transition(authority, scope, reference, setOf(NoticeState.HANDED_OFF), NoticeState.ACKNOWLEDGED,
            "acknowledged_at")

    fun fail(authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID, failureClass: String) {
        require(failureClass.matches(Regex("[a-z0-9._-]{1,64}"))) { "Failure class is not a safe code" }
        transition(authority, scope, reference, setOf(NoticeState.QUEUED, NoticeState.HANDED_OFF),
            NoticeState.FAILED, "handed_off_at", failureClass)
    }

    private fun transition(
        authority: TrustedNoticeAuthority, scope: ProfileScope, reference: UUID,
        from: Set<NoticeState>, to: NoticeState, column: String, failureClass: String? = null,
    ) {
        require(authority.app == scope.application()) { "Notice authority is outside the profile application" }
        transactions.executeWithoutResult {
            lockedPolicy(jdbc, mapper, scope.application()) ?: throw IllegalStateException("Application is not registered")
            val stored = persistence.find(scope, reference) ?: throw IllegalStateException("Notice intent is unavailable")
            require(stored.state in from) { "Notice transition is not allowed from ${stored.state}" }
            require(persistence.transition(scope, reference, from, to, column, clock.instant(), failureClass))
        }
    }

    /** Bound to the closure instance, so a profile closed again after reactivation gets fresh notices. */
    private fun dedupeIdentity(scope: ProfileScope, noticeClass: NoticeClass, deadline: Instant): String =
        MessageDigest.getInstance("SHA-256").digest(
            mapper.writeValueAsBytes(listOf(scope.issuerEnvironmentRef.toString(), scope.appReference.toString(),
                scope.subjectReference.toString(), noticeClass.name, deadline.toString())))
            .joinToString("") { "%02x".format(it) }
}
