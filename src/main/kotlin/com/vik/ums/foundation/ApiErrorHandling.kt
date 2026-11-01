package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Framework-level authentication failures still answer the contract's safe envelope. */
class SafeAuthenticationEntryPoint(private val mapper: ObjectMapper) : AuthenticationEntryPoint {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, failure: AuthenticationException) {
        val correlation = SafeErrors.correlation(response, request)
        SafeErrors.write(mapper, response, 401, "unauthenticated", "Authentication is required", correlation)
    }
}

/** Framework-level authorization failures use the same envelope, never a default error page. */
class SafeAccessDeniedHandler(private val mapper: ObjectMapper) : AccessDeniedHandler {
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, denied: AccessDeniedException) {
        val correlation = SafeErrors.correlation(response, request)
        SafeErrors.write(mapper, response, 403, "forbidden", "The action is not permitted", correlation)
    }
}

/** Last-resort mapping for unhandled MVC failures; messages never echo input or internals. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = SafeErrors.correlation(response, request)
        return ResponseEntity.status(503)
            .body(SafeErrors.body("temporarily_unavailable", "The service is temporarily unavailable", correlation))
    }

    @ExceptionHandler(Exception::class)
    fun internal(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = SafeErrors.correlation(response, request)
        return ResponseEntity.status(500)
            .body(SafeErrors.body("internal_error", "The request could not be completed", correlation))
    }
}
