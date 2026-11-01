package com.vik.ums.foundation

import java.util.UUID

/** Canonical UUID only; the JDK parser also accepts short forms the contract does not. */
object StrictIds {
    private val CANONICAL = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun uuid(value: String?): UUID? =
        value?.takeIf { it.matches(CANONICAL) }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
