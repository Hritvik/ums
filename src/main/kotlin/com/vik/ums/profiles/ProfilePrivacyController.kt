package com.vik.ums.profiles

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.foundation.SafeErrors
import com.vik.ums.foundation.StrictIds
import com.vik.ums.iam.Assurance
import com.vik.ums.iam.CallerActorReferences
import com.vik.ums.iam.CallerContext
import com.vik.ums.privacy.DeletionCancellationCommand
import com.vik.ums.privacy.DeletionRequestCommand
import com.vik.ums.privacy.PrivacyPurpose
import com.vik.ums.privacy.ProfileDeletionService
import com.vik.ums.privacy.TrustedPrivacyAuthority
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * App-only deletion commands. Recent Strong authority is derived from the token's assurance and
 * authentication time, never from a caller-supplied value.
 */
@RestController
class ProfilePrivacyController(
    private val deletion: ProfileDeletionService,
    private val scopes: ProfileScopeResolver,
    private val mapper: ObjectMapper,
    private val maximumStrongAge: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val strict = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @PostMapping("/api/v1/profile/deletion-requests")
    fun request(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> =
        command(request, response, PrivacyPurpose.REQUEST_DELETION, requestReference = null)

    @PostMapping("/api/v1/profile/deletion-requests/{requestId}/cancellation")
    fun cancel(@PathVariable requestId: String, request: HttpServletRequest,
               response: HttpServletResponse): ResponseEntity<Any> {
        val reference = StrictIds.uuid(requestId)
            ?: return failure(response, 404, "unavailable", "Deletion request is unavailable",
                SafeErrors.correlation(response, request))
        return command(request, response, PrivacyPurpose.CANCEL_DELETION, reference)
    }

    /** The published collection read carries the operation reference as a query parameter. */
    @GetMapping("/api/v1/profile/deletion-requests")
    fun statusByQuery(@RequestParam("operationReference", required = false) reference: String?,
                      request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val parsed = StrictIds.uuid(reference)
            ?: return failure(response, 400, "malformed_input", "A valid operationReference is required",
                SafeErrors.correlation(response, request))
        return statusOf(parsed, request, response)
    }

    @GetMapping("/api/v1/profile/deletion-requests/{requestId}")
    fun status(@PathVariable requestId: String, request: HttpServletRequest,
               response: HttpServletResponse): ResponseEntity<Any> {
        val parsed = StrictIds.uuid(requestId)
            ?: return failure(response, 404, "unavailable", "Deletion request is unavailable",
                SafeErrors.correlation(response, request))
        return statusOf(parsed, request, response)
    }

    private fun statusOf(reference: UUID, request: HttpServletRequest,
                         response: HttpServletResponse): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        val correlation = SafeErrors.correlation(response, request)
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Deletion request is unavailable", correlation)
        val authority = authority(caller, scope, PrivacyPurpose.READ_DELETION_STATUS)
            ?: return failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
        return try {
            ResponseEntity.ok(mapOf("data" to deletion.status(authority, reference)))
        } catch (unavailable: IllegalStateException) {
            failure(response, 404, "unavailable", "Deletion request is unavailable", correlation)
        } catch (invalid: IllegalArgumentException) {
            failure(response, 404, "unavailable", "Deletion request is unavailable", correlation)
        }
    }

    private fun command(request: HttpServletRequest, response: HttpServletResponse,
                        purpose: PrivacyPurpose, requestReference: UUID?): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        val correlation = SafeErrors.correlation(response, request)
        if (request.contentType?.startsWith("application/json") != true) {
            return failure(response, 400, "malformed_input", "A JSON body is required", correlation)
        }
        val key = StrictIds.uuid(request.getHeader("Idempotency-Key"))
            ?: return failure(response, 400, "malformed_input", "A valid Idempotency-Key is required", correlation)
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) {
            return failure(response, 400, "malformed_input", "The request body is too large", correlation)
        }
        val version = parseVersion(body)
            ?: return failure(response, 400, "malformed_input", "The request body is malformed", correlation)

        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        val authority = authority(caller, scope, purpose)
            ?: return failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
        return try {
            val accepted = when (purpose) {
                PrivacyPurpose.REQUEST_DELETION -> deletion.request(authority,
                    DeletionRequestCommand(key, version, correlation))
                PrivacyPurpose.CANCEL_DELETION -> deletion.cancel(authority,
                    DeletionCancellationCommand(requestReference!!, key, version, correlation))
                PrivacyPurpose.READ_DELETION_STATUS -> throw IllegalStateException("Unsupported command")
            }
            ResponseEntity.status(202).body(mapOf("data" to accepted))
        } catch (reused: IllegalArgumentException) {
            if (reused.message?.contains("idempotency") == true) {
                failure(response, 409, "idempotency_key_reused", "The idempotency key was reused", correlation)
            } else failure(response, 403, "forbidden", "Recent strong authentication is required", correlation)
        } catch (conflict: IllegalStateException) {
            val message = conflict.message.orEmpty()
            when {
                message.contains("version", ignoreCase = true) ->
                    failure(response, 409, "version_conflict", "The profile changed", correlation)
                message.contains("unavailable", ignoreCase = true) ->
                    failure(response, 404, "unavailable", "Profile is unavailable", correlation)
                else -> failure(response, 409, "version_conflict", "The request cannot be applied", correlation)
            }
        }
    }

    private fun authority(caller: CallerContext, scope: ProfileScope,
                          purpose: PrivacyPurpose): TrustedPrivacyAuthority? {
        if (caller.assurance != Assurance.STRONG) return null
        val authenticatedAt = caller.authenticatedAt ?: return null
        val now = clock.instant()
        // Keep a margin so a re-sample inside the service cannot flip an accepted command to denied.
        val acceptable = maximumStrongAge.minusSeconds(1).coerceAtLeast(Duration.ofSeconds(1))
        if (authenticatedAt.isAfter(now) || Duration.between(authenticatedAt, now) > acceptable) return null
        return TrustedPrivacyAuthority(scope, CallerActorReferences.of(caller), purpose,
            authenticatedAt, strong = true, expiresAt = authenticatedAt.plus(maximumStrongAge))
    }

    private fun parseVersion(body: ByteArray): Long? = runCatching {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
        val root = strict.readTree(text)
        require(root.isObject && root.fieldNames().asSequence().toSet() == setOf("expectedVersion"))
        val version = root.get("expectedVersion")
        require(version.isIntegralNumber && version.canConvertToLong() && version.longValue() > 0)
        version.longValue()
    }.getOrNull()

    private fun failure(response: HttpServletResponse, status: Int, code: String, message: String,
                        correlation: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(SafeErrors.body(code, message, correlation))

    private companion object {
        const val MAX_BODY = 16 * 1024
    }
}
