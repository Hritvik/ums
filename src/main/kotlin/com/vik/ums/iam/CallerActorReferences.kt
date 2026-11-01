package com.vik.ums.iam

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable local reference for one registered caller. It is a name-based identifier for audit and
 * idempotency scoping inside UMS, never an IAM subject, actor, or global identity.
 */
object CallerActorReferences {
    fun of(caller: CallerContext): UUID {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ums-caller:${caller.appId}:${caller.clientId}".toByteArray())
        val bytes = ByteBuffer.wrap(digest.copyOf(16))
        val high = bytes.long
        val low = bytes.long
        return UUID((high and -0x1001L) or 0x5000L, (low and 0x3fffffffffffffffL) or Long.MIN_VALUE)
    }
}
