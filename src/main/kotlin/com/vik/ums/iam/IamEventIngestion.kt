package com.vik.ums.iam

import com.vik.ums.profiles.ProvisioningOutcome
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A payload the transport has already attributed to one registered producer and app.
 * Producer authentication (workload certificate or signed request) is the transport gate;
 * this type only carries the resolved identity, never a caller-supplied claim of it.
 */
data class TrustedProducerRequest(
    val applicationId: String,
    val producer: String,
    val correlationId: String,
    val body: ByteArray,
) {
    init {
        require(applicationId.matches(Regex("[a-z][a-z0-9-]{2,63}"))) { "Application id is malformed" }
        require(producer.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "Producer is malformed" }
        require(correlationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Correlation id is malformed" }
        require(body.isNotEmpty()) { "Payload is empty" }
    }
}

enum class IngestionOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    RECONCILIATION_REQUIRED,
    NOT_ADOPTED,
    RESTRICTED,
    QUARANTINED,
}

data class IngestionResult(val outcome: IngestionOutcome, val quarantineReference: UUID? = null)

/** The transport seam: one already-attributed request in, one bounded outcome out. */
fun interface IamIngest {
    fun ingest(request: TrustedProducerRequest): IngestionResult
}

/**
 * Bounded, strict inbound processing for registered IAM events. Malformed or unattributable
 * payloads are quarantined with a safe class and a digest; they are never retried forever and
 * never block later valid events. Gaps return reconciliation rather than provisioning.
 */
class IamEventIngestionService(
    private val jdbc: JdbcTemplate,
    private val adapter: IamOnboardingAdapter,
    private val registeredProducer: String,
    private val maximumBytes: Int = 32 * 1024,
    private val clock: Clock = Clock.systemUTC(),
) : IamIngest {
    init {
        require(registeredProducer.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "Registered producer is malformed" }
        require(maximumBytes in 1..(1024 * 1024)) { "Payload bound must be between 1 byte and 1 MiB" }
    }

    override fun ingest(request: TrustedProducerRequest): IngestionResult {
        if (request.producer != registeredProducer) return quarantine(request, "PRODUCER_MISMATCH")
        if (request.body.size > maximumBytes) return quarantine(request, "PAYLOAD_TOO_LARGE")
        val json = decode(request.body) ?: return quarantine(request, "INVALID_ENCODING")
        return try {
            IngestionResult(adapter.consume(json, request.applicationId).outcome.toIngestion())
        } catch (unregistered: UnregisteredIamApplicationException) {
            quarantine(request, "APPLICATION_NOT_REGISTERED")
        } catch (mismatch: IamApplicationMismatchException) {
            quarantine(request, "APPLICATION_BINDING_MISMATCH")
        } catch (malformed: IllegalArgumentException) {
            // Strict parsing rejects before any durable write.
            quarantine(request, "SCHEMA_INVALID")
        }
    }

    fun resolve(reference: UUID) {
        val updated = jdbc.update("""update ums_iam_ingestion_quarantine set state = 'RESOLVED', resolved_at = ?
            where quarantine_reference = ? and state = 'OPEN'""", Timestamp.from(clock.instant()), reference)
        require(updated == 1) { "Quarantined payload is unavailable" }
    }

    fun openQuarantineCount(): Int =
        jdbc.queryForObject("""select count(*) from ums_iam_ingestion_quarantine where state = 'OPEN'""",
            Int::class.java)!!

    fun oldestOpenReceivedAt(): Instant? = jdbc.query("""select min(received_at) from ums_iam_ingestion_quarantine
        where state = 'OPEN'""", { rs, _ -> rs.getTimestamp(1)?.toInstant() }).singleOrNull()

    private fun quarantine(request: TrustedProducerRequest, failureClass: String): IngestionResult {
        val reference = UUID.randomUUID()
        jdbc.update("""insert into ums_iam_ingestion_quarantine
            (quarantine_reference, producer, application_id, payload_digest, failure_class,
             correlation_id, state, received_at)
            values (?, ?, ?, ?, ?, ?, 'OPEN', ?)""",
            reference, request.producer, request.applicationId, digest(request.body), failureClass,
            request.correlationId, Timestamp.from(clock.instant()))
        return IngestionResult(IngestionOutcome.QUARANTINED, reference)
    }

    private fun digest(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    private fun decode(body: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    }.getOrNull()

    private fun ProvisioningOutcome.toIngestion(): IngestionOutcome = when (this) {
        ProvisioningOutcome.PROVISIONED -> IngestionOutcome.APPLIED
        ProvisioningOutcome.DUPLICATE -> IngestionOutcome.DUPLICATE
        ProvisioningOutcome.STALE -> IngestionOutcome.STALE
        ProvisioningOutcome.RECONCILIATION_REQUIRED -> IngestionOutcome.RECONCILIATION_REQUIRED
        ProvisioningOutcome.NOT_ADOPTED -> IngestionOutcome.NOT_ADOPTED
        ProvisioningOutcome.RESTRICTED -> IngestionOutcome.RESTRICTED
    }
}
