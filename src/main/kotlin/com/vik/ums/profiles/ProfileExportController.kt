package com.vik.ums.profiles

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.foundation.SafeErrors
import com.vik.ums.foundation.StrictIds
import com.vik.ums.iam.Assurance
import com.vik.ums.iam.CallerActorReferences
import com.vik.ums.privacy.ProfileExportService
import com.vik.ums.privacy.TrustedExportAuthority
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Scoped export: request, status, single-use capability, and download. */
@RestController
class ProfileExportController(
    private val exports: ProfileExportService,
    private val scopes: ProfileScopeResolver,
    private val mapper: ObjectMapper,
    private val maximumStrongAge: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val strict = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @PostMapping("/api/v1/profile/exports")
    fun request(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = begin(request, response)
        val key = StrictIds.uuid(request.getHeader("Idempotency-Key"))
            ?: return failure(response, 400, "malformed_input", "A valid Idempotency-Key is required", correlation)
        val authority = authority(request, correlation) ?: return authorityFailure(request, response, correlation)
        return try {
            ResponseEntity.status(202).body(mapOf("data" to exports.request(authority, key)))
        } catch (unavailable: IllegalStateException) {
            failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        } catch (invalid: IllegalArgumentException) {
            failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
        }
    }

    @GetMapping("/api/v1/profile/exports")
    fun status(@RequestParam("operationReference", required = false) reference: String?,
               request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = begin(request, response)
        val parsed = StrictIds.uuid(reference)
            ?: return failure(response, 400, "malformed_input", "A valid operationReference is required", correlation)
        val authority = authority(request, correlation) ?: return authorityFailure(request, response, correlation)
        return try {
            ResponseEntity.ok(mapOf("data" to exports.status(authority, parsed)))
        } catch (unavailable: IllegalStateException) {
            failure(response, 404, "unavailable", "Export is unavailable", correlation)
        }
    }

    @PostMapping("/api/v1/profile/exports/{exportReference}/downloads")
    fun capability(@PathVariable exportReference: String, request: HttpServletRequest,
                   response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = begin(request, response)
        val reference = StrictIds.uuid(exportReference)
            ?: return failure(response, 404, "unavailable", "Export is unavailable", correlation)
        val authority = authority(request, correlation) ?: return authorityFailure(request, response, correlation)
        return try {
            ResponseEntity.status(201).body(mapOf("data" to exports.capability(authority, reference)))
        } catch (unavailable: IllegalStateException) {
            failure(response, 404, "unavailable", "Export is unavailable", correlation)
        } catch (invalid: IllegalArgumentException) {
            failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
        }
    }

    /**
     * The capability authorizes the read, but the caller must still be the same app subject: the
     * token travels in the body so it never appears in a URL, access log, or trace span.
     */
    @PostMapping("/api/v1/profile/exports/downloads")
    fun download(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val correlation = begin(request, response)
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Export download is unavailable", correlation)
        val token = token(request)
            ?: return failure(response, 400, "malformed_input", "A valid capability token is required", correlation)
        return try {
            ResponseEntity.ok(mapOf("data" to exports.download(scope, token)))
        } catch (unavailable: IllegalStateException) {
            failure(response, 404, "unavailable", "Export download is unavailable", correlation)
        }
    }

    private fun begin(request: HttpServletRequest, response: HttpServletResponse): String {
        response.setHeader("Cache-Control", "no-store")
        return SafeErrors.correlation(response, request)
    }

    /** Mirrors the service window exactly, so a boundary request cannot become a server error. */
    private fun authority(request: HttpServletRequest, correlation: String): TrustedExportAuthority? {
        val caller = CallerAuthorityInterceptor.caller(request) ?: return null
        val scope = scopes.resolve(caller) ?: return null
        val authenticatedAt = caller.authenticatedAt ?: return null
        if (caller.assurance != Assurance.STRONG) return null
        val now = clock.instant()
        val expiresAt = authenticatedAt.plus(maximumStrongAge)
        if (authenticatedAt.isAfter(now) || !now.isBefore(expiresAt)) return null
        return TrustedExportAuthority(scope, CallerActorReferences.of(caller), authenticatedAt, true, expiresAt)
    }

    private fun authorityFailure(request: HttpServletRequest, response: HttpServletResponse,
                                 correlation: String): ResponseEntity<Any> {
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        if (scopes.resolve(caller) == null) {
            return failure(response, 404, "unavailable", "Export is unavailable", correlation)
        }
        return failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
    }

    /** Null means a malformed body; the token must be exactly the issued 43-character shape. */
    private fun token(request: HttpServletRequest): String? {
        if (request.contentType?.startsWith("application/json") != true) return null
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) return null
        return runCatching {
            val root = strict.readValue(body, JsonNode::class.java)
            require(root.isObject && root.fieldNames().asSequence().toSet() == setOf("token"))
            val value = root.get("token")
            require(value.isTextual && TOKEN.matches(value.textValue()))
            value.textValue()
        }.getOrNull()
    }

    private fun failure(response: HttpServletResponse, status: Int, code: String, message: String,
                        correlation: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(SafeErrors.body(code, message, correlation))

    private companion object {
        const val MAX_BODY = 4 * 1024
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
