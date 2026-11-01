package com.vik.ums.foundation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.servlet.NoHandlerFoundException

class ApiErrorHandlingTest {
    private val mapper = jacksonObjectMapper()
    private val request = MockHttpServletRequest("GET", "/api/v1/profile")
    private val response = MockHttpServletResponse()

    @Test fun `framework authentication and authorization failures use the safe envelope`() {
        SafeAuthenticationEntryPoint(mapper).commence(request, response, BadCredentialsException("no"))
        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).contains("unauthenticated").contains("correlationId")

        val denied = MockHttpServletResponse()
        SafeAccessDeniedHandler(mapper).handle(request, denied, AccessDeniedException("no"))
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.contentAsString).contains("forbidden").doesNotContain("denied")
    }

    @Test fun `a database outage answers a retryable envelope with Retry-After`() {
        val advice = ApiExceptionHandler()
        val result = advice.unavailable(request, response)

        assertThat(result.statusCode.value()).isEqualTo(503)
        assertThat(response.getHeader("Retry-After")).isEqualTo("5")
        assertThat(((result.body as Map<*, *>)["error"] as Map<*, *>)["code"]).isEqualTo("temporarily_unavailable")
    }

    @Test fun `unhandled failures answer a safe internal error`() {
        val result = ApiExceptionHandler().internal(IllegalStateException("secret internal detail"),
            request, response)

        assertThat(result.statusCode.value()).isEqualTo(500)
        assertThat((result.body as Map<*, *>)["correlationId"]).isNotNull()
        assertThat(result.body.toString()).doesNotContain("secret internal detail")
    }

    @Test fun `framework status codes map to their contract codes`() {
        fun codeFor(status: org.springframework.http.HttpStatus): String {
            val exception = org.springframework.web.server.ResponseStatusException(status)
            val result = ApiExceptionHandler().internal(exception, request, MockHttpServletResponse())
            return ((result.body as Map<*, *>)["error"] as Map<*, *>)["code"] as String
        }
        assertThat(codeFor(org.springframework.http.HttpStatus.UNAUTHORIZED)).isEqualTo("unauthenticated")
        assertThat(codeFor(org.springframework.http.HttpStatus.FORBIDDEN)).isEqualTo("forbidden")
        assertThat(codeFor(org.springframework.http.HttpStatus.CONFLICT)).isEqualTo("version_conflict")
        assertThat(codeFor(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)).isEqualTo("rate_limited")
        assertThat(codeFor(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo("malformed_input")
    }

    @Test fun `method not allowed keeps its status and Allow header`() {
        val notAllowed = org.springframework.web.HttpRequestMethodNotSupportedException("PUT", listOf("GET", "POST"))
        val result = ApiExceptionHandler().internal(notAllowed, request, response)

        assertThat(result.statusCode.value()).isEqualTo(405)
        assertThat(response.getHeader("Allow")).contains("GET").contains("POST")
        assertThat(((result.body as Map<*, *>)["error"] as Map<*, *>)["code"]).isEqualTo("malformed_input")
    }

    @Test fun `unsupported media types keep their 4xx meaning`() {
        val unsupported = org.springframework.web.HttpMediaTypeNotSupportedException("text/plain")
        val result = ApiExceptionHandler().internal(unsupported, request, response)

        assertThat(result.statusCode.value()).isEqualTo(415)
        assertThat(((result.body as Map<*, *>)["error"] as Map<*, *>)["code"]).isEqualTo("malformed_input")
    }

    @Test fun `framework 4xx exceptions keep their status instead of becoming 500`() {
        val notFound = NoHandlerFoundException("GET", "/missing", org.springframework.http.HttpHeaders())
        val result = ApiExceptionHandler().internal(notFound, request, response)

        assertThat(result.statusCode.value()).isEqualTo(404)
        assertThat(((result.body as Map<*, *>)["error"] as Map<*, *>)["code"]).isEqualTo("unavailable")
    }
}
