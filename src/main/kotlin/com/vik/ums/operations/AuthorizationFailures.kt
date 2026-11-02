package com.vik.ums.operations

import java.util.concurrent.atomic.AtomicLong

/** Counts caller denials so a repeated authorization failure becomes an alert, never a payload. */
class AuthorizationFailures {
    private val denials = AtomicLong()

    fun record() {
        denials.incrementAndGet()
    }

    fun count(): Long = denials.get()
}
