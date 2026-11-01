package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.foundation.SafeErrors
import com.vik.ums.iam.CallerActorReferences
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Read-only display for the caller's own profile. Mutations are not shipped yet. */
@RestController
class ProfileController(
    private val editing: ProfileEditingService,
    private val scopes: ProfileScopeResolver,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/api/v1/profile")
    fun profile(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        val correlation = SafeErrors.correlation(response, request)
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        val display = try {
            editing.display(TrustedHumanAuthority(scope, CallerActorReferences.of(caller), setOf(HumanAction.READ)))
        } catch (unavailable: IllegalStateException) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        } catch (invalid: IllegalArgumentException) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        }
        return ResponseEntity.ok(mapOf("data" to display))
    }

    private fun failure(response: HttpServletResponse, status: Int, code: String, message: String,
                        correlation: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(SafeErrors.body(code, message, correlation))
}
