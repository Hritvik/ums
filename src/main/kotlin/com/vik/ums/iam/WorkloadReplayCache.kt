package com.vik.ums.iam

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded replay guard for a mutating workload route: one `jti` may be used for one idempotency
 * key. Re-delivering the same key is allowed (the ingest dedupe decides it); using the same token
 * for a different key is rejected. Entries expire with the token and the map is capped.
 *
 * This is per-instance. A multi-replica deployment needs the same guard in shared storage; until
 * then the ingress replicas must not both accept the same token, which the deployment records.
 */
class WorkloadReplayCache(
    private val clock: Clock = Clock.systemUTC(),
    private val maximumEntries: Int = 10_000,
) {
    private val seen = ConcurrentHashMap<String, Entry>()

    init { require(maximumEntries in 1..1_000_000) { "Replay cache bound is out of range" } }

    fun accept(jti: String, idempotencyKey: String, expiresAt: Instant): Boolean {
        val now = clock.instant()
        if (seen.size >= maximumEntries) purge(now)
        var accepted = false
        seen.compute(jti) { _, existing ->
            if (existing == null || !existing.expiresAt.isAfter(now)) {
                accepted = true
                Entry(idempotencyKey, expiresAt)
            } else {
                accepted = existing.idempotencyKey == idempotencyKey
                existing
            }
        }
        return accepted
    }

    private fun purge(now: Instant) {
        seen.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        if (seen.size >= maximumEntries) seen.clear()
    }

    private data class Entry(val idempotencyKey: String, val expiresAt: Instant)
}
