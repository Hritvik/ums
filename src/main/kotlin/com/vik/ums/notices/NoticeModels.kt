package com.vik.ums.notices

import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import java.time.Instant
import java.util.UUID

/** Registered notice-adapter or maintenance workload. A trusted local seam, not a token. */
data class TrustedNoticeAuthority(val app: AppScope, val actorReference: UUID)

enum class NoticeClass(val windowDays: Long) {
    CLOSURE_EXPIRY_14_DAY(14),
    CLOSURE_EXPIRY_7_DAY(7),
}

enum class NoticeState { QUEUED, HANDED_OFF, ACKNOWLEDGED, FAILED, SUPPRESSED, WINDOW_PASSED }

enum class NoticeOutcome {
    QUEUED,

    /** The same class was already queued for this closure; nothing changed. */
    ALREADY_QUEUED,

    /** The window start has already passed; a late-observed closure never shifts it. */
    WINDOW_PASSED,

    /** There is no closed profile with a published retention deadline. */
    NOT_CLOSED,

    UNAVAILABLE,
}

data class NoticeResult(
    val outcome: NoticeOutcome,
    val noticeClass: NoticeClass,
    val noticeReference: UUID? = null,
    val deadline: Instant? = null,
)

internal data class StoredNotice(
    val reference: UUID,
    val scope: ProfileScope,
    val noticeClass: NoticeClass,
    val deadline: Instant,
    val state: NoticeState,
    val dedupeIdentity: String,
)
