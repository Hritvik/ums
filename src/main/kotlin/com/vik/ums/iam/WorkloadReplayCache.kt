package com.vik.ums.iam

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One `jti` is accepted once. IAM mints a fresh token for every delivery attempt (the event id, not
 * the credential, is the retry key), so any reuse of a `jti` is a replay and is rejected. Entries
 * expire with the token plus the resolver's clock skew, and the map is capped; at capacity a new
 * token is refused rather than evicting a live entry.
 *
 * This is per-instance. A multi-replica ingress needs the same guard in shared storage; until then
 * the deployment runs one accepting replica per audience, matching the relay's single destination.
 */
class WorkloadReplayCache(
    private val clock: Clock = Clock.systemUTC(),
    private val maximumEntries: Int = 10_000,
) {
    private val seen = ConcurrentHashMap<String, Instant>()

    init { require(maximumEntries in 1..1_000_000) { "Replay cache bound is out of range" } }

    /** True only for the first use of this `jti`; false for a replay or at capacity. */
    fun accept(jti: String, expiresAt: Instant): Boolean {
        val now = clock.instant()
        val entryExpiry = expiresAt.plusSeconds(WorkloadTokenProfile.CLOCK_SKEW_SECONDS)
        if (seen.size >= maximumEntries) seen.entries.removeIf { !it.value.isAfter(now) }
        var accepted = false
        seen.compute(jti) { _, existing ->
            when {
                existing != null && existing.isAfter(now) -> {
                    accepted = false
                    existing
                }
                existing == null && seen.size >= maximumEntries -> {
                    accepted = false
                    null
                }
                else -> {
                    accepted = true
                    entryExpiry
                }
            }
        }
        return accepted
    }
}
