package com.vik.ums.profiles

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.foundation.CallerAuthorityInterceptor
import com.vik.ums.foundation.SafeErrors
import com.vik.ums.foundation.StrictIds
import com.vik.ums.iam.CallerActorReferences
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Targeted field update with explicit version and idempotency. Body is bounded and strict. */
@RestController
class ProfilePatchController(
    private val editing: ProfileEditingService,
    private val provisioning: ProfileProvisioningService,
    private val scopes: ProfileScopeResolver,
    private val mapper: ObjectMapper,
) {
    private val strict = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @PatchMapping("/api/v1/profile")
    fun patch(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        response.setHeader("Cache-Control", "no-store")
        val correlation = SafeErrors.correlation(response, request)
        val caller = CallerAuthorityInterceptor.caller(request)
            ?: return failure(response, 401, "unauthenticated", "Authentication is required", correlation)
        val scope = scopes.resolve(caller)
            ?: return failure(response, 404, "unavailable", "Profile is unavailable", correlation)

        if (request.contentType?.startsWith("application/json") != true) {
            return failure(response, 400, "malformed_input", "A JSON body is required", correlation)
        }
        val idempotencyKey = StrictIds.uuid(request.getHeader("Idempotency-Key"))
            ?: return failure(response, 400, "malformed_input", "A valid Idempotency-Key is required", correlation)
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) {
            return failure(response, 400, "malformed_input", "The request body is too large", correlation)
        }
        val command = parse(body, idempotencyKey, correlation)
            ?: return failure(response, 400, "malformed_input", "The request body is malformed", correlation)

        val status = provisioning.status(AppScope(scope.issuerEnvironmentRef, scope.appReference), scope)
        if (status.state?.lifecycle != Lifecycle.ACTIVE) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        }
        // The same authority reads back the display it just wrote.
        val authority = TrustedHumanAuthority(scope, CallerActorReferences.of(caller),
            setOf(HumanAction.EDIT, HumanAction.READ))
        try {
            editing.patch(authority, command)
        } catch (conflict: ProfileIdempotencyConflictException) {
            return failure(response, 409, "idempotency_key_reused", "The idempotency key was reused", correlation)
        } catch (conflict: ProfileVersionConflictException) {
            return failure(response, 409, "version_conflict", "The profile changed", correlation)
        } catch (invalid: IllegalArgumentException) {
            if (invalid.message?.contains("capability", ignoreCase = true) == true) {
                return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
            }
            return failure(response, 422, "invalid_field", "A supplied field value is not allowed", correlation)
        } catch (unavailable: IllegalStateException) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        }
        val display = try {
            editing.display(authority)
        } catch (unavailable: IllegalStateException) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        } catch (invalid: IllegalArgumentException) {
            return failure(response, 404, "unavailable", "Profile is unavailable", correlation)
        }
        return ResponseEntity.ok(mapOf("data" to display))
    }

    /** Null means the body is malformed; known fields only, explicit nulls preserved. */
    private fun parse(body: ByteArray, idempotencyKey: UUID, correlation: String): ProfilePatchCommand? = runCatching {
        val root = strict.readValue(body, JsonNode::class.java)
        require(root.isObject && root.fieldNames().asSequence().toSet() == setOf("expectedVersion", "changes"))
        val version = root.get("expectedVersion")
        require(version.isIntegralNumber && version.canConvertToLong() && version.longValue() > 0)
        val changes = root.get("changes")
        require(changes.isObject && changes.size() > 0)
        val mapped = changes.fieldNames().asSequence().associate { name ->
            val field = FIELD_BY_WIRE[name] ?: throw IllegalArgumentException("Unknown field")
            val value = changes.get(name)
            require(value.isNull || value.isTextual)
            field to value.takeUnless { it.isNull }?.textValue()
        }
        ProfilePatchCommand(idempotencyKey, version.longValue(), mapped, correlation)
    }.getOrNull()

    private fun failure(response: HttpServletResponse, status: Int, code: String, message: String,
                        correlation: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(SafeErrors.body(code, message, correlation))

    private companion object {
        const val MAX_BODY = 16 * 1024
        val FIELD_BY_WIRE: Map<String, ProfileField> =
            ProfileField.entries.associateBy { it.wireName }
    }
}
