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
        assertThat(response.contentAsString).isEmpty()
    }

    @Test fun `unhandled failures answer a safe internal error`() {
        val result = ApiExceptionHandler().internal(IllegalStateException("secret internal detail"),
            request, response)

        assertThat(result.statusCode.value()).isEqualTo(500)
        assertThat((result.body as Map<*, *>)["correlationId"]).isNotNull()
        assertThat(result.body.toString()).doesNotContain("secret internal detail")
    }

    @Test fun `unsupported media types keep their 4xx meaning`() {
        val unsupported = org.springframework.web.HttpMediaTypeNotSupportedException("text/plain")
        val result = ApiExceptionHandler().internal(unsupported, request, response)

        assertThat(result.statusCode.value()).isEqualTo(415)
        assertThat((result.body as Map<*, *>)["error"].toString()).contains("malformed_input")
    }

    @Test fun `framework 4xx exceptions keep their status instead of becoming 500`() {
        val notFound = NoHandlerFoundException("GET", "/missing", org.springframework.http.HttpHeaders())
        val result = ApiExceptionHandler().internal(notFound, request, response)

        assertThat(result.statusCode.value()).isEqualTo(404)
        assertThat((result.body as Map<*, *>)["error"].toString()).contains("unavailable")
    }
}
