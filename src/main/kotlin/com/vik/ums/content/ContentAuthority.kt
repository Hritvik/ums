package com.vik.ums.content

import com.vik.ums.profiles.ProfileScope

enum class ContentVerdict { VALID, REVOKED, UNAVAILABLE, OWNER_MISMATCH }

/** A reference the content authority accepted for this scope; it authorizes exactly one attach. */
data class TrustedContentAttachment(val reference: String, val scope: ProfileScope)

/**
 * The content owner's ownership and cleanup seam. Implementations must be called outside any
 * profile transaction; an unconfigured deployment fails closed.
 */
interface ContentAuthority {
    fun check(reference: String, scope: ProfileScope): ContentVerdict

    /**
     * True only when the owner explicitly confirmed cleanup (`confirmed: true`); a transport-level
     * acknowledgement is not confirmation, so false leaves the obligation pending.
     */
    fun requestCleanup(reference: String, scope: ProfileScope): Boolean
}

/** No content owner configured: attachment is rejected and cleanup stays visibly pending. */
class UnconfiguredContentAuthority : ContentAuthority {
    override fun check(reference: String, scope: ProfileScope): ContentVerdict = ContentVerdict.UNAVAILABLE

    override fun requestCleanup(reference: String, scope: ProfileScope): Boolean = false
}

/** The reference shape the accepted contract allows; also enforced at the HTTP boundary. */
object ContentReference {
    val PATTERN = Regex("^asset_[A-Za-z0-9_-]{16,240}$")
}
