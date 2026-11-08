package com.vik.ums.iam

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A short-lived credential for the IAM snapshot route; never the human token endpoint. */
data class WorkloadCredential(
    val token: String,
    val audience: String,
    val scopes: Set<String>,
    val expiresAt: Instant,
)

/** Supplies a credential minted by IAM for a registered workload binding. */
fun interface SnapshotCredentialSupplier {
    fun credential(): WorkloadCredential?
}

enum class SnapshotCredentialDenial { MISSING, EXPIRED, WRONG_AUDIENCE, WRONG_SCOPE }

sealed interface SnapshotFetch {
    data class Page(val body: ByteArray, val correlationId: String) : SnapshotFetch

    /** Retryable failures are 408/429/5xx, timeouts, and no response; anything else is terminal. */
    data class Failed(val retryable: Boolean, val failureClass: String) : SnapshotFetch

    /** The credential was absent, expired, or outside the pinned audience/scope. No call was made. */
    data class RefusedCredential(val reason: SnapshotCredentialDenial) : SnapshotFetch
}

/**
 * Reads one page of the IAM onboarding snapshot over `registeredWorkloadToken`.
 *
 * The client authenticates with a workload credential supplied by the deployment; it never calls
 * the human token endpoint. The body is bounded, the correlation is generated (not copied from a
 * human request), and a missing/expired/wrong-scope credential fails before any network call.
 */
class IamSnapshotClient(
    baseUri: URI,
    private val applicationId: String,
    private val credentials: SnapshotCredentialSupplier,
    private val clock: Clock = Clock.systemUTC(),
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maximumBytes: Int = 256 * 1024,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) {
    private val base: URI

    init {
        require(timeout > Duration.ZERO && timeout <= Duration.ofMinutes(2)) { "Snapshot timeout is out of range" }
        require(maximumBytes in 1..(4 * 1024 * 1024)) { "Snapshot body bound is out of range" }
        require(applicationId.matches(WorkloadTokenProfile.APP_ID)) { "Snapshot application is malformed" }
        require(baseUri.scheme == "https" || (baseUri.scheme == "http" && baseUri.host in LOOPBACK)) {
            "Snapshot endpoint must be HTTPS outside a loopback test"
        }
        require(baseUri.userInfo == null && baseUri.query == null && baseUri.fragment == null) {
            "Snapshot endpoint must not carry credentials, a query, or a fragment"
        }
        base = URI.create(baseUri.toString().trimEnd('/') + "/")
    }

    fun fetch(cursor: String? = null): SnapshotFetch {
        val credential = credentials.credential()
            ?: return SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.MISSING)
        if (credential.token.isBlank() || credential.token.length > 8192) {
            return SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.MISSING)
        }
        if (!credential.expiresAt.isAfter(clock.instant())) {
            return SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.EXPIRED)
        }
        if (credential.audience != WorkloadTokenProfile.SNAPSHOT_AUDIENCE) {
            return SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.WRONG_AUDIENCE)
        }
        if (WorkloadTokenProfile.SNAPSHOT_SCOPE !in credential.scopes) {
            return SnapshotFetch.RefusedCredential(SnapshotCredentialDenial.WRONG_SCOPE)
        }
        if (cursor != null && (cursor.isEmpty() || cursor.length > 512)) {
            return SnapshotFetch.Failed(false, "INVALID_CURSOR")
        }
        val path = "internal/v1/apps/$applicationId/onboarding" +
            (cursor?.let { "?cursor=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: "")
        val correlation = "cor_${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Authorization", "Bearer ${credential.token}")
            .header("Accept", "application/json")
            .header("X-Correlation-ID", correlation)
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            if (status !in 200..299) {
                response.body().close()
                return SnapshotFetch.Failed(
                    retryable = status == 408 || status == 429 || status in 500..599,
                    failureClass = if (status in 200..299) "OK" else "HTTP_$status",
                )
            }
            val body = response.body().use { it.readNBytes(maximumBytes + 1) }
            if (body.size > maximumBytes) SnapshotFetch.Failed(false, "RESPONSE_TOO_LARGE")
            else SnapshotFetch.Page(body, correlation)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            SnapshotFetch.Failed(true, "INTERRUPTED")
        } catch (unavailable: Exception) {
            SnapshotFetch.Failed(true, "UNAVAILABLE")
        }
    }

    private companion object {
        val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")
    }
}
