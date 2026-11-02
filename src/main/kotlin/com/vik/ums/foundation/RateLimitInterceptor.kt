package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.iam.CallerContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded per-app/subject limits for the user-facing surface. It is deliberately in-process:
 * when shared limit infrastructure is degraded the local window is still enforced, at a stricter
 * rate, so a bulk reader cannot starve privacy operations.
 */
class RateLimitInterceptor(
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val readsPerMinute: Int = 60,
    private val writesPerMinute: Int = 20,
    private val privacyPerMinute: Int = 10,
    private val degraded: Boolean = false,
) : HandlerInterceptor {
    private val limiter = FixedWindowRateLimiter(clock)

    init {
        require(readsPerMinute > 0 && writesPerMinute > 0 && privacyPerMinute > 0)
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val caller = CallerAuthorityInterceptor.caller(request) ?: return true
        val bucket = bucket(request)
        val limit = when (bucket) {
            Bucket.READ -> if (degraded) degrade(readsPerMinute) else readsPerMinute
            Bucket.WRITE -> if (degraded) degrade(writesPerMinute) else writesPerMinute
            Bucket.PRIVACY -> if (degraded) degrade(privacyPerMinute) else privacyPerMinute
        }
        val key = "${caller.appId}|${caller.subjectReference}|$bucket"
        if (limiter.allow(key, limit)) return true
        val correlation = SafeErrors.correlation(response, request)
        response.setHeader("Retry-After", "60")
        SafeErrors.write(mapper, response, 429, "rate_limited", "Too many requests", correlation)
        return false
    }

    private fun degrade(limit: Int): Int = maxOf(1, limit / 5)

    private fun bucket(request: HttpServletRequest): Bucket {
        val path = request.requestURI
        return when {
            path.startsWith("/api/v1/profile/deletion-requests") ||
                path.startsWith("/api/v1/profile/exports") -> Bucket.PRIVACY
            request.method.equals("GET", ignoreCase = true) -> Bucket.READ
            else -> Bucket.WRITE
        }
    }

    private enum class Bucket { READ, WRITE, PRIVACY }
}

/** Fixed one-minute window; bounded memory by evicting windows older than two minutes. */
class FixedWindowRateLimiter(private val clock: Clock, private val window: Duration = Duration.ofMinutes(1)) {
    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String, limit: Int): Boolean {
        val now = clock.instant()
        val current = windows.compute(key) { _, existing ->
            if (existing == null || !now.isBefore(existing.start.plus(window))) Window(now, 1)
            else existing.copy(count = existing.count + 1)
        }!!
        if (windows.size > MAX_KEYS) {
            windows.entries.removeIf { !now.isBefore(it.value.start.plus(window.multipliedBy(2))) }
        }
        return current.count <= limit
    }

    fun size(): Int = windows.size

    private data class Window(val start: Instant, val count: Int)

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
