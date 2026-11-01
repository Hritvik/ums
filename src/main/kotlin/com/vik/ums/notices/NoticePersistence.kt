package com.vik.ums.notices

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class SuppressedNotice(
    val reference: UUID,
    val noticeClass: NoticeClass,
    val deadline: Instant,
    val dedupeIdentity: String,
    val revision: Long,
)

internal class NoticePersistence(private val jdbc: JdbcTemplate) {
    fun find(scope: ProfileScope, reference: UUID): StoredNotice? = jdbc.query("""select notice_reference,
        notice_class, deadline, state, dedupe_identity from ums_profile_notice_intents
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and notice_reference = ?""",
        { rs, _ -> StoredNotice(rs.getObject(1, UUID::class.java), scope,
            NoticeClass.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), NoticeState.valueOf(rs.getString(4)),
            rs.getString(5)) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference).singleOrNull()

    fun findByDedupe(scope: ProfileScope, dedupeIdentity: String): StoredNotice? = jdbc.query("""select notice_reference,
        notice_class, deadline, state, dedupe_identity from ums_profile_notice_intents
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and dedupe_identity = ?""",
        { rs, _ -> StoredNotice(rs.getObject(1, UUID::class.java), scope,
            NoticeClass.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), NoticeState.valueOf(rs.getString(4)),
            rs.getString(5)) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, dedupeIdentity).singleOrNull()

    fun insert(scope: ProfileScope, reference: UUID, noticeClass: NoticeClass, deadline: Instant,
               dedupeIdentity: String, state: NoticeState, now: Instant, closureReference: UUID) {
        jdbc.update("""insert into ums_profile_notice_intents
            (notice_reference, issuer_environment_ref, app_reference, subject_reference, notice_class,
             deadline, dedupe_identity, state, queued_at, notice_revision, closure_reference,
             window_passed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""",
            reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            noticeClass.name, Timestamp.from(deadline), dedupeIdentity, state.name, Timestamp.from(now),
            closureReference,
            if (state == NoticeState.WINDOW_PASSED) Timestamp.from(now) else null)
    }

    /**
     * Conditional transition with an operation revision increment. Returns the new revision,
     * or null when the stored state no longer allows it.
     */
    fun transition(scope: ProfileScope, reference: UUID, from: Set<NoticeState>, to: NoticeState,
                   column: String, now: Instant, failureClass: String? = null,
                   lateAfterSuppression: Boolean = false): Long? {
        require(column in setOf("handed_off_at", "acknowledged_at", "suppressed_at", "failed_at"))
        val fromList = from.joinToString(",") { "'$it'" }
        val extra = buildString {
            if (failureClass != null) append(", failure_class = ?")
            if (lateAfterSuppression) append(", delivered_after_suppression = true")
        }
        val args = mutableListOf<Any>(to.name, Timestamp.from(now))
        if (failureClass != null) args += failureClass
        args.addAll(listOf(scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference))
        return jdbc.query("""update ums_profile_notice_intents set state = ?, $column = ?, notice_revision = notice_revision + 1$extra
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and notice_reference = ? and state in ($fromList) returning notice_revision""",
            { rs, _ -> rs.getLong(1) }, *args.toTypedArray()).singleOrNull()
    }

    fun suppress(scope: ProfileScope, now: Instant): List<SuppressedNotice> = jdbc.query("""update ums_profile_notice_intents
        set state = 'SUPPRESSED', suppressed_at = ?, notice_revision = notice_revision + 1
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
        and state in ('QUEUED', 'HANDED_OFF', 'FAILED') returning notice_reference, notice_class, deadline,
        dedupe_identity, notice_revision""",
        { rs, _ -> SuppressedNotice(rs.getObject(1, UUID::class.java), NoticeClass.valueOf(rs.getString(2)),
            rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getLong(5)) },
        Timestamp.from(now), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)

    /** Only the live closure instance is eligible: a stale suppressed intent must not be revived. */
    fun suppressed(scope: ProfileScope, closureReference: UUID): List<SuppressedNotice> = jdbc.query("""select notice_reference,
        notice_class, deadline, dedupe_identity, notice_revision from ums_profile_notice_intents
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and state = 'SUPPRESSED'
        and closure_reference = ?""",
        { rs, _ -> SuppressedNotice(rs.getObject(1, UUID::class.java), NoticeClass.valueOf(rs.getString(2)),
            rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getLong(5)) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, closureReference)

    /** Re-arms a suppressed intent when the obligation that suppressed it is withdrawn. */
    fun revive(scope: ProfileScope, reference: UUID, state: NoticeState, now: Instant): Long? =
        jdbc.query("""update ums_profile_notice_intents
            set state = ?, suppressed_at = null, window_passed_at = ?, failed_at = null, failure_class = null,
            delivered_after_suppression = false, notice_revision = notice_revision + 1
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and notice_reference = ? and state = 'SUPPRESSED' returning notice_revision""",
            { rs, _ -> rs.getLong(1) }, state.name,
            if (state == NoticeState.WINDOW_PASSED) Timestamp.from(now) else null,
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference).singleOrNull()

    /**
     * Monotonic per-scope stream position. Held under the application lock, and independent of
     * outbox retention so pruning published rows cannot restart the counter.
     */
    fun nextStreamRevision(scope: ProfileScope): Long = jdbc.queryForObject("""update ums_profile_bindings
        set notice_stream_revision = notice_stream_revision + 1
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
        returning notice_stream_revision""", Long::class.java,
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)!!
}

/**
 * One minimized fact per notice state change. The owner learns that an intent was created,
 * handed off, acknowledged, failed, or withdrawn, so it never sends a notice for data that
 * was already erased.
 */
internal fun appendNoticeFact(
    jdbc: JdbcTemplate, mapper: ObjectMapper, environment: String, scope: ProfileScope,
    reference: UUID, noticeClass: NoticeClass, deadline: Instant, dedupeIdentity: String,
    state: NoticeState, revision: Long, correlationId: String, occurredAt: Instant,
) {
    val payload = mapper.writeValueAsString(mapOf(
        "noticeReference" to reference, "schemaVersion" to "1", "type" to "ums.profile.notice-intent.v1",
        "producer" to "ums", "environment" to environment, "occurredAt" to occurredAt.toString(),
        "correlationId" to correlationId, "scope" to scope, "noticeClass" to noticeClass.name,
        "deadline" to deadline.toString(), "dedupeIdentity" to dedupeIdentity,
        "state" to state.name, "noticeRevision" to revision.toString(),
    ))
    val persistence = NoticePersistence(jdbc)
    val streamId = "${scope.issuerEnvironmentRef}:${scope.appReference}:${scope.subjectReference}:notice"
    val streamRevision = persistence.nextStreamRevision(scope)
    jdbc.update("""insert into ums_event_outbox
        (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
        values (?, ?, ?, ?, 'ums.profile.notice-intent.v1', ?::jsonb, ?)""",
        UUID.randomUUID(), scope.appReference.toString(), streamId, streamRevision, payload,
        Timestamp.from(occurredAt))
}

/** Re-arms intents whose suppressing obligation was withdrawn, and publishes the change. */
internal fun rearmNotices(
    jdbc: JdbcTemplate, mapper: ObjectMapper, environment: String, scope: ProfileScope,
    closureReference: UUID, correlationId: String, occurredAt: Instant,
) {
    val persistence = NoticePersistence(jdbc)
    persistence.suppressed(scope, closureReference).forEach { notice ->
        val windowStart = notice.deadline.minusSeconds(notice.noticeClass.windowDays * 86_400)
        val target = if (occurredAt.isAfter(windowStart)) NoticeState.WINDOW_PASSED else NoticeState.QUEUED
        val revision = persistence.revive(scope, notice.reference, target, occurredAt) ?: return@forEach
        appendNoticeFact(jdbc, mapper, environment, scope, notice.reference, notice.noticeClass, notice.deadline,
            notice.dedupeIdentity, target, revision, correlationId, occurredAt)
    }
}

/** Suppresses outstanding intents and publishes one withdrawal fact per affected notice. */
internal fun suppressNotices(
    jdbc: JdbcTemplate, mapper: ObjectMapper, environment: String, scope: ProfileScope,
    correlationId: String, occurredAt: Instant,
) {
    NoticePersistence(jdbc).suppress(scope, occurredAt).forEach {
        appendNoticeFact(jdbc, mapper, environment, scope, it.reference, it.noticeClass, it.deadline,
            it.dedupeIdentity, NoticeState.SUPPRESSED, it.revision, correlationId, occurredAt)
    }
}
