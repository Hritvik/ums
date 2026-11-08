package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WorkloadReplayCacheTest {
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun `one jti may deliver one event, and a retry of that event is allowed`() {
        val cache = WorkloadReplayCache(clock)
        val expiry = now.plusSeconds(300)
        assertThat(cache.accept("jti-0123456789abcdef", "evt_0123456789abcdef01", expiry)).isTrue()
        assertThat(cache.accept("jti-0123456789abcdef", "evt_0123456789abcdef01", expiry)).isTrue()
        assertThat(cache.accept("jti-0123456789abcdef", "evt_0123456789abcdef02", expiry)).isFalse()
    }

    @Test fun `an expired entry is forgotten and the cache is bounded`() {
        val cache = WorkloadReplayCache(clock, maximumEntries = 2)
        assertThat(cache.accept("jti-a-0123456789abcdef", "evt_0123456789abcdef01", now.plusSeconds(1))).isTrue()
        assertThat(cache.accept("jti-b-0123456789abcdef", "evt_0123456789abcdef02", now.plusSeconds(300))).isTrue()
        // A later pass past the first expiry can reuse the id without growing the map.
        val later = WorkloadReplayCache(Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC), maximumEntries = 1)
        assertThat(later.accept("jti-a-0123456789abcdef", "evt_0123456789abcdef01", now.plusSeconds(1))).isTrue()
        assertThat(later.accept("jti-b-0123456789abcdef", "evt_0123456789abcdef02",
            now.plusSeconds(300))).isTrue()
        assertThat(later.accept("jti-c-0123456789abcdef", "evt_0123456789abcdef03",
            now.plusSeconds(300))).isTrue()
    }

    @Test fun `the bound is validated`() {
        assertThat(runCatching { WorkloadReplayCache(clock, maximumEntries = 0) }.isFailure).isTrue()
        assertThat(runCatching { WorkloadReplayCache(clock, maximumEntries = 1_000_001) }.isFailure).isTrue()
    }

    @Test fun `entries expire with the token`() {
        val cache = WorkloadReplayCache(clock)
        assertThat(cache.accept("jti-0123456789abcdef", "evt_0123456789abcdef01", now)).isTrue()
        val after = WorkloadReplayCache(Clock.fixed(now.plus(Duration.ofSeconds(1)), ZoneOffset.UTC))
        assertThat(after.accept("jti-0123456789abcdef", "evt_0123456789abcdef02", now.plusSeconds(5))).isTrue()
    }
}
