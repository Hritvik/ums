package com.vik.ums.privacy

/** Recognized expected local fences only; unexpected defects and data integrity failures are not classified as blocked. */
enum class FinalizationBlockReason {
    APP_UNAVAILABLE, REQUEST_UNAVAILABLE, PROFILE_UNAVAILABLE, CANCELLED, SUPERSEDED,
    GENERATION_MISMATCH, NOT_DUE, CONTENT_AUTHORITY_UNAVAILABLE,
}
class FinalizationBlockedException(val reason: FinalizationBlockReason) :
    IllegalStateException("Local deletion finalization blocked: ${reason.name}")

internal fun requireFinalization(condition: Boolean, reason: FinalizationBlockReason) {
    if (!condition) throw FinalizationBlockedException(reason)
}
