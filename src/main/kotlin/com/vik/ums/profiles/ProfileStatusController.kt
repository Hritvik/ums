package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.foundation.SafeErrors
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * First shipped business read. Authentication and caller authority are established by the
 * filter; this controller only answers for the exact mapped app and subject.
 */
@RestController
class ProfileStatusController(
    private val provisioning: ProfileProvisioningService,
    private val scopes: ProfileScopeResolver,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/api/v1/profile/status")
    fun status(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        val correlation = SafeErrors.correlation(response, request)
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        val status = provisioning.status(AppScope(scope.issuerEnvironmentRef, scope.appReference), scope)
        return ResponseEntity.ok(status)
    }

    private fun failure(response: HttpServletResponse, status: Int, code: String, message: String,
                        correlation: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(SafeErrors.body(code, message, correlation))
}
