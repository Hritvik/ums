package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/**
 * Bounds unauthenticated traffic by remote address before the security chain. The caller-aware
 * interceptor limits authenticated use; this filter makes sure a forged, expired, or malformed
 * token cannot be hammered without any budget at all.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class PreAuthRateLimitFilter(
    private val mapper: ObjectMapper,
    clock: Clock,
    private val perMinute: Int = 120,
    private val degraded: Boolean = false,
) : OncePerRequestFilter() {
    private val limiter = FixedWindowRateLimiter(clock)

    init { require(perMinute > 0) }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse,
                                  filterChain: FilterChain) {
        val limit = if (degraded) maxOf(1, perMinute / 5) else perMinute
        val key = "addr|${request.remoteAddr ?: "unknown"}"
        if (limiter.allow(key, limit)) {
            filterChain.doFilter(request, response)
            return
        }
        val correlation = SafeErrors.correlation(response, request)
        response.setHeader("Retry-After", "60")
        SafeErrors.write(mapper, response, 429, "rate_limited", "Too many requests", correlation)
    }
}
