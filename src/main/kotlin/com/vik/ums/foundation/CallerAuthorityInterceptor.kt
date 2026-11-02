package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.iam.CallerContext
import com.vik.ums.iam.CallerDeniedException
import com.vik.ums.iam.IamCallerResolver
import com.vik.ums.operations.AuthorizationFailures
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Turns the already signature-checked token into caller authority before a business handler
 * runs. Runs inside MVC, after the security chain has authenticated the request.
 */
class CallerAuthorityInterceptor(
    private val resolver: IamCallerResolver,
    private val mapper: ObjectMapper,
    private val failures: AuthorizationFailures = AuthorizationFailures(),
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        val caller = try {
            authentication?.let { resolver.resolve(it.token) }
        } catch (denied: CallerDeniedException) {
            failures.record()
            null
        }
        if (caller == null) {
            val correlation = SafeErrors.correlation(response, request)
            SafeErrors.write(mapper, response, 401, "unauthenticated", "Authentication is required", correlation)
            return false
        }
        request.setAttribute(ATTRIBUTE, caller)
        return true
    }

    companion object {
        const val ATTRIBUTE = "ums.caller"
        fun caller(request: HttpServletRequest): CallerContext? = request.getAttribute(ATTRIBUTE) as? CallerContext
    }
}
