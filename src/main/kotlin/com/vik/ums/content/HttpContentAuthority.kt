package com.vik.ums.content

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Calls the content owner's ownership check and cleanup endpoint. Both calls happen outside the
 * profile transaction; the request carries the app registration and app subject, never a URL.
 */
class HttpContentAuthority(
    private val baseUri: URI,
    private val mapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
    private val bearerToken: String? = null,
) : ContentAuthority {
    init { require(baseUri.scheme == "http" || baseUri.scheme == "https") { "Content authority must be HTTP(S)" } }

    override fun check(reference: String, scope: ProfileScope): ContentVerdict {
        val identity = identity(scope) ?: return ContentVerdict.UNAVAILABLE
        val response = post("ownership-checks", mapOf(
            "reference" to reference,
            "appRegistrationId" to identity.first,
            "appSubject" to identity.second,
        )) ?: return ContentVerdict.UNAVAILABLE
        val validField = response["valid"]
        val valid = validField?.isBoolean == true && validField.asBoolean()
        val state = response["state"]?.asText()
        val ownerApp = response["appRegistrationId"]?.asText()
        val ownerSubject = response["appSubject"]?.asText()
        return when {
            // An outage is never a revocation, even when the owner also reports valid:false.
            state == "UNAVAILABLE" -> ContentVerdict.UNAVAILABLE
            state == "REVOKED" -> ContentVerdict.REVOKED
            // An unrecognised or partial body must never destroy an association; only an explicit
            // answer can do that.
            validField?.isBoolean != true -> ContentVerdict.UNAVAILABLE
            !valid -> ContentVerdict.REVOKED
            ownerApp == null || ownerSubject == null -> ContentVerdict.UNAVAILABLE
            ownerApp != identity.first || ownerSubject != identity.second -> ContentVerdict.OWNER_MISMATCH
            state == "READY" -> ContentVerdict.VALID
            else -> ContentVerdict.UNAVAILABLE
        }
    }

    override fun requestCleanup(reference: String, scope: ProfileScope): Boolean {
        val identity = identity(scope) ?: return false
        val response = post("cleanup-requests", mapOf(
            "reference" to reference,
            "appRegistrationId" to identity.first,
            "appSubject" to identity.second,
        )) ?: return false
        // An acknowledgement ("accepted", 202) is not a confirmation; only an explicit answer is.
        return response["confirmed"]?.asBoolean() == true
    }

    private fun identity(scope: ProfileScope): Pair<String, String>? =
        jdbc.query("""select a.app_registration_id, s.app_subject
            from ums_iam_subject_mappings s join ums_iam_app_mappings a
              on a.issuer_environment_ref = s.issuer_environment_ref and a.app_reference = s.app_reference
            where s.issuer_environment_ref = ? and s.app_reference = ? and s.subject_reference = ?""",
            { rs, _ -> rs.getString(1) to rs.getString(2) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()

    private fun post(path: String, body: Map<String, Any>): JsonNode? {
        val builder = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) null else mapper.readTree(response.body())
        } catch (unavailable: Exception) {
            null
        }
    }
}
