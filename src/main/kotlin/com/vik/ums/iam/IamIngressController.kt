package com.vik.ums.iam

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Bounded inbound onboarding delivery for the IAM relay. The security chain has already verified
 * the signature and issuer; this handler enforces the workload profile and binding, then delegates
 * to the ingestion service. Every authority failure is the same non-enumerating 404.
 */
@RestController
@ConditionalOnProperty(name = ["ums.iam.ingress.enabled"], havingValue = "true")
class IamIngressController(
    private val resolver: WorkloadCallerResolver,
    private val ingestion: IamIngest,
    private val replay: WorkloadReplayCache = WorkloadReplayCache(),
    @param:Value("\${ums.iam.ingress.max-body-bytes:32768}") private val maxBodyBytes: Int = 32 * 1024,
) {
    init { require(maxBodyBytes in 1..(1024 * 1024)) { "Ingress body bound must be between 1 byte and 1 MiB" } }

    @PostMapping("/internal/v1/iam/events", consumes = ["application/json"])
    fun ingest(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Map<String, Any>> {
        val correlation = correlation(request.getHeader(CORRELATION_HEADER))
        response.setHeader(CORRELATION_HEADER, correlation)
        val resolution = jwt?.let(resolver::resolve) as? WorkloadResolution.Accepted
            ?: return unavailable(correlation)
        if (idempotencyKey?.matches(EVENT_ID) != true) {
            return ResponseEntity.status(422).body(mapOf("outcome" to "INVALID_IDEMPOTENCY_KEY",
                "correlationId" to correlation))
        }
        val body = try {
            readBounded(request.inputStream, maxBodyBytes)
        } catch (aborted: java.io.IOException) {
            return ResponseEntity.status(400).body(mapOf("outcome" to "REQUEST_ABORTED",
                "correlationId" to correlation))
        } ?: return ResponseEntity.status(413).body(mapOf("outcome" to "PAYLOAD_TOO_LARGE",
            "correlationId" to correlation))
        if (body.isEmpty()) {
            return ResponseEntity.status(400).body(mapOf("outcome" to "EMPTY_BODY",
                "correlationId" to correlation))
        }
        val bodyEventId = eventId(body)
        if (bodyEventId != null && bodyEventId != idempotencyKey) {
            return ResponseEntity.status(422).body(mapOf("outcome" to "IDEMPOTENCY_KEY_MISMATCH",
                "correlationId" to correlation))
        }
        if (!replay.accept(resolution.principal.jti, resolution.principal.expiresAt)) {
            return ResponseEntity.status(409).body(mapOf("outcome" to "REPLAY",
                "correlationId" to correlation))
        }
        val result = ingestion.ingest(TrustedProducerRequest(
            applicationId = resolution.principal.appRegistrationId,
            producer = PRODUCER,
            correlationId = correlation,
            body = body,
        ))
        val status = when (result.outcome) {
            IngestionOutcome.APPLIED, IngestionOutcome.DUPLICATE, IngestionOutcome.STALE -> 202
            IngestionOutcome.QUARANTINED -> 422
            IngestionOutcome.RECONCILIATION_REQUIRED, IngestionOutcome.RESTRICTED,
            IngestionOutcome.NOT_ADOPTED -> 409
        }
        return ResponseEntity.status(status).body(mapOf(
            "outcome" to result.outcome.name,
            "correlationId" to correlation,
        ).let { body -> result.quarantineReference?.let { body + ("quarantineReference" to it) } ?: body })
    }

    /** The association report requires an alphanumeric first character; a bad value is replaced. */
    private fun correlation(supplied: String?): String =
        supplied?.takeIf { it.matches(CORRELATION) } ?: "cor_${UUID.randomUUID()}"

    private fun unavailable(correlation: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(404).body(mapOf("error" to "unavailable", "correlationId" to correlation))

    private fun eventId(body: ByteArray): String? = runCatching {
        val node = MAPPER.readTree(body)
        node?.get("eventId")?.takeIf { it.isTextual }?.textValue()
    }.getOrNull()

    /** Reads at most [maximum] bytes; a larger stream is refused instead of buffered without bound. */
    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream(minOf(maximum, 8192))
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maximum) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val PRODUCER = "iam"
        const val CORRELATION_HEADER = "X-Correlation-ID"
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
        val CORRELATION = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val EVENT_ID = Regex("^evt_[A-Za-z0-9_-]{16,128}$")
    }
}
