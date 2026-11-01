package com.vik.ums.notices

import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class NoticePersistence(private val jdbc: JdbcTemplate) {
    fun find(scope: ProfileScope, reference: UUID): StoredNotice? = jdbc.query("""select notice_reference,
        notice_class, deadline, state from ums_profile_notice_intents
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and notice_reference = ?""",
        { rs, _ -> StoredNotice(rs.getObject(1, UUID::class.java), scope,
            NoticeClass.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), NoticeState.valueOf(rs.getString(4))) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference).singleOrNull()

    fun findByDedupe(scope: ProfileScope, dedupeIdentity: String): UUID? = jdbc.query("""select notice_reference
        from ums_profile_notice_intents where issuer_environment_ref = ? and app_reference = ?
        and subject_reference = ? and dedupe_identity = ?""",
        { rs, _ -> rs.getObject(1, UUID::class.java) },
        scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, dedupeIdentity).singleOrNull()

    fun insert(scope: ProfileScope, reference: UUID, noticeClass: NoticeClass, deadline: Instant,
               dedupeIdentity: String, now: Instant) {
        jdbc.update("""insert into ums_profile_notice_intents
            (notice_reference, issuer_environment_ref, app_reference, subject_reference, notice_class,
             deadline, dedupe_identity, state, queued_at)
            values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)""",
            reference, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference,
            noticeClass.name, Timestamp.from(deadline), dedupeIdentity, Timestamp.from(now))
    }

    /** Conditional transition: returns false when the stored state no longer allows it. */
    fun transition(scope: ProfileScope, reference: UUID, from: Set<NoticeState>, to: NoticeState,
                   column: String, now: Instant, failureClass: String? = null): Boolean {
        require(column in setOf("handed_off_at", "acknowledged_at", "suppressed_at"))
        val fromList = from.joinToString(",") { "'$it'" }
        val extra = if (failureClass == null) "" else ", failure_class = ?"
        val args = mutableListOf<Any>(to.name, Timestamp.from(now))
        if (failureClass != null) args += failureClass
        args.addAll(listOf(scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference))
        return jdbc.update("""update ums_profile_notice_intents set state = ?, $column = ?$extra
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and notice_reference = ? and state in ($fromList)""", *args.toTypedArray()) == 1
    }

    /** Suppression is idempotent and only touches notices that have not been acknowledged. */
    fun suppress(scope: ProfileScope, now: Instant): Int = jdbc.update("""update ums_profile_notice_intents
        set state = 'SUPPRESSED', suppressed_at = ?
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
        and state in ('QUEUED', 'HANDED_OFF', 'FAILED')""",
        Timestamp.from(now), scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)

    fun appendIntent(applicationId: String, streamId: String, eventId: UUID, payload: String, occurredAt: Instant) {
        val revision = jdbc.queryForObject("""select coalesce(max(stream_revision), 0) + 1 from ums_event_outbox
            where application_id = ? and stream_id = ?""", Long::class.java, applicationId, streamId)!!
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
            values (?, ?, ?, ?, 'ums.profile.notice-intent.v1', ?::jsonb, ?)""",
            eventId, applicationId, streamId, revision, payload, Timestamp.from(occurredAt))
    }
}
