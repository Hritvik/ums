package com.vik.ums.iam

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkloadReplayCacheTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val expiry = now.plusSeconds(300)

    @Test fun `a jti is accepted once and any reuse is a replay`() {
        val cache = WorkloadReplayCache(clock)
        assertThat(cache.accept("jti-0123456789abcdef", expiry)).isTrue()
        assertThat(cache.accept("jti-0123456789abcdef", expiry)).isFalse()
        assertThat(cache.accept("jti-fedcba9876543210", expiry)).isTrue()
    }

    @Test fun `an entry expires with the token plus the clock skew`() {
        val moving = MutableClock(now)
        val cache = WorkloadReplayCache(moving)
        // The token expired 120s ago, so exp + 60s is already in the past: the jti is reusable.
        assertThat(cache.accept("jti-0123456789abcdef", now.minusSeconds(120))).isTrue()
        assertThat(cache.accept("jti-0123456789abcdef", now.minusSeconds(120))).isTrue()
        // A live token is not reusable, even after the clock advances inside its lifetime.
        assertThat(cache.accept("jti-fedcba9876543210", now.plusSeconds(300))).isTrue()
        moving.now = now.plusSeconds(120)
        assertThat(cache.accept("jti-fedcba9876543210", now.plusSeconds(300))).isFalse()
    }

    @Test fun `at capacity a new jti is refused rather than evicting a live one`() {
        val cache = WorkloadReplayCache(clock, maximumEntries = 1)
        assertThat(cache.accept("jti-a-0123456789abcdef", expiry)).isTrue()
        assertThat(cache.accept("jti-b-0123456789abcdef", expiry)).isFalse()
        assertThat(cache.accept("jti-a-0123456789abcdef", expiry)).isFalse()
    }

    @Test fun `the bound is validated`() {
        assertThat(runCatching { WorkloadReplayCache(clock, maximumEntries = 0) }.isFailure).isTrue()
        assertThat(runCatching { WorkloadReplayCache(clock, maximumEntries = 1_000_001) }.isFailure).isTrue()
    }
}
