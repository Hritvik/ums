package com.vik.ums.foundation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.iam.Assurance
import com.vik.ums.iam.CallerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RateLimitTest {
    private val clock = AdjustableClock(Instant.parse("2026-11-02T09:00:00Z"))
    private val caller = CallerContext("dutch-development", "aps_0123456789abcdefghijkL", "dutch-mobile",
        Assurance.STRONG, 1, "urn:distracted:aal2", listOf("pwd"), setOf("profile:read"), "jti_1", clock.instant())

    private fun request(path: String, method: String): MockHttpServletRequest =
        MockHttpServletRequest(method, path).apply {
            setAttribute(CallerAuthorityInterceptor.ATTRIBUTE, caller)
        }

    private fun interceptor(reads: Int = 2, writes: Int = 1, privacy: Int = 1, degraded: Boolean = false) =
        RateLimitInterceptor(jacksonObjectMapper(), clock, reads, writes, privacy, degraded)

    @Test fun `the fixed window allows the limit then refuses and resets`() {
        val limiter = FixedWindowRateLimiter(clock)

        assertThat(listOf(1, 2, 3).map { limiter.allow("k", 2) }).containsExactly(true, true, false)

        clock.advance(Duration.ofMinutes(1))
        assertThat(limiter.allow("k", 2)).isTrue()
        assertThat(limiter.size()).isEqualTo(1)
    }

    @Test fun `reads, writes, and privacy commands get separate budgets`() {
        val interceptor = interceptor(reads = 1, writes = 1, privacy = 1)

        assertThat(interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())).isFalse()
        assertThat(interceptor.preHandle(request("/api/v1/profile", "PATCH"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile/deletion-requests", "POST"),
            MockHttpServletResponse(), Any())).isTrue()
    }

    @Test fun `a refusal is a safe error envelope with retry guidance`() {
        val interceptor = interceptor(privacy = 1)
        val first = MockHttpServletResponse()
        val second = MockHttpServletResponse()

        interceptor.preHandle(request("/api/v1/profile/exports", "POST"), first, Any())
        val allowed = interceptor.preHandle(request("/api/v1/profile/exports", "POST"), second, Any())

        assertThat(allowed).isFalse()
        assertThat(second.status).isEqualTo(429)
        assertThat(second.getHeader("Retry-After")).isEqualTo("60")
        assertThat(second.getHeader("Cache-Control")).isNull()
        assertThat(second.contentAsString).contains("\"code\":\"rate_limited\"").doesNotContain("aps_")
    }

    @Test fun `degraded limit infrastructure tightens the window instead of removing it`() {
        val interceptor = interceptor(reads = 10, degraded = true)

        assertThat(interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())).isFalse()
    }

    @Test fun `a request without caller authority is left to the authority interceptor`() {
        val interceptor = interceptor(reads = 1)

        assertThat(interceptor.preHandle(MockHttpServletRequest("GET", "/api/v1/profile"),
            MockHttpServletResponse(), Any())).isTrue()
    }

    @Test fun `degraded privacy commands also tighten`() {
        val interceptor = interceptor(privacy = 10, degraded = true)

        assertThat(interceptor.preHandle(request("/api/v1/profile/exports", "POST"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile/exports", "POST"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/api/v1/profile/exports", "POST"), MockHttpServletResponse(), Any())).isFalse()
    }

    @Test fun `another app subject does not consume this caller's budget`() {
        val interceptor = interceptor(reads = 1)
        interceptor.preHandle(request("/api/v1/profile", "GET"), MockHttpServletResponse(), Any())

        val other = request("/api/v1/profile", "GET").apply {
            setAttribute(CallerAuthorityInterceptor.ATTRIBUTE, caller.copy(subjectReference = "aps_other0000000000000000"))
        }

        assertThat(interceptor.preHandle(other, MockHttpServletResponse(), Any())).isTrue()
    }

    @Test fun `unauthenticated traffic is bounded before the security chain`() {
        val filter = PreAuthRateLimitFilter(jacksonObjectMapper(), clock, perMinute = 1)
        val first = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/profile").apply { remoteAddr = "10.0.0.1" },
            first) { _, _ -> }
        val second = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/profile").apply { remoteAddr = "10.0.0.1" },
            second) { _, _ -> }

        assertThat(first.status).isEqualTo(200)
        assertThat(second.status).isEqualTo(429)

        val other = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/profile").apply { remoteAddr = "10.0.0.2" },
            other) { _, _ -> }
        assertThat(other.status).isEqualTo(200)

        val health = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/actuator/health"), health) { _, _ -> }
        assertThat(health.status).isEqualTo(200)
    }

    @Test fun `the interceptor is wired after caller authority in the MVC chain`() {
        val limiter = interceptor(reads = 1)
        val callerStub = object : org.springframework.web.servlet.HandlerInterceptor {
            override fun preHandle(request: jakarta.servlet.http.HttpServletRequest,
                                   response: jakarta.servlet.http.HttpServletResponse, handler: Any): Boolean {
                request.setAttribute(CallerAuthorityInterceptor.ATTRIBUTE, caller)
                return true
            }
        }
        val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(Probe()).addInterceptors(callerStub, limiter).build()

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/probe"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/probe"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests)
    }

    @org.springframework.web.bind.annotation.RestController
    private class Probe {
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/probe")
        fun probe(): Map<String, Boolean> = mapOf("ok" to true)
    }

    private class AdjustableClock(@Volatile private var now: Instant) : Clock() {
        fun advance(duration: Duration) { now = now.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
