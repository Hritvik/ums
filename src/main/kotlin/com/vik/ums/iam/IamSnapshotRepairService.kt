package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** A snapshot page the transport has already attributed to one registered producer and app. */
data class SnapshotRepairRequest(
    val applicationId: String,
    val producer: String,
    val subjectReference: String,
    val correlationId: String,
    val body: ByteArray,
) {
    init {
        require(applicationId.matches(Regex("[a-z][a-z0-9-]{2,63}"))) { "Application id is malformed" }
        require(producer.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "Producer is malformed" }
        require(subjectReference.matches(Regex("aps_[A-Za-z0-9_-]{22,128}"))) { "Subject reference is malformed" }
        require(correlationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Correlation id is malformed" }
        require(body.isNotEmpty()) { "Payload is empty" }
    }
}

enum class SnapshotRepairOutcome {
    /** No local profile exists for the subject; repair never creates one. */
    NO_LOCAL_BINDING,

    /** The snapshot has no ACTIVE item for the subject. */
    NO_EVIDENCE,

    /** The local checkpoint is current; nothing changed. */
    NO_CHANGE,

    /** The local checkpoint was advanced to the snapshot version. */
    CONVERGED,

    /** The pinned activation disagrees; a human/provider decision is required. */
    RECONCILIATION_REQUIRED,

    /** An erased profile is never restored from a snapshot. */
    REFUSED_ERASED,

    /** A closed or deletion-pending profile is never reopened from a snapshot. */
    REFUSED_RESTRICTED,

    /** Malformed, unattributable, or oversized payload. */
    REJECTED,
}

data class SnapshotRepairResult(val outcome: SnapshotRepairOutcome, val checkpoint: Long? = null)

/**
 * Repairs one app-scoped binding from the accepted IAM onboarding snapshot.
 *
 * The snapshot is authoritative for an ACTIVE onboarding version, not for identity, deletion,
 * or closure. It may advance a lagging checkpoint; it never creates a profile, restores an
 * erased one, clears a cause, or reopens a closed profile. Pinned activation disagreement is
 * reported for reconciliation instead of being overwritten.
 */
class IamSnapshotRepairService(
    private val jdbc: JdbcTemplate,
    private val source: TrustedIamSource,
    private val registeredProducer: String,
    private val maximumBytes: Int = 256 * 1024,
) {
    private val mapper = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    init {
        require(registeredProducer.matches(Regex("[a-z][a-z0-9-]{1,63}"))) { "Registered producer is malformed" }
        require(maximumBytes in 1..(1024 * 1024)) { "Payload bound must be between 1 byte and 1 MiB" }
    }

    fun repair(request: SnapshotRepairRequest): SnapshotRepairResult {
        if (request.producer != registeredProducer) return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)
        if (request.body.size > maximumBytes) return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)
        val json = decode(request.body) ?: return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)
        val item = runCatching { parse(json, request.applicationId, request.subjectReference) }
            .getOrElse { return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED) }
            ?: return SnapshotRepairResult(SnapshotRepairOutcome.NO_EVIDENCE)

        val appReference = jdbc.query("""select app_reference from ums_iam_app_mappings
            where issuer_environment_ref = ? and app_registration_id = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            source.issuerEnvironmentRef, request.applicationId).singleOrNull()
            ?: return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)

        val mapping = jdbc.query("""select subject_reference, activation_id from ums_iam_subject_mappings
            where issuer_environment_ref = ? and app_reference = ? and app_subject = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
            source.issuerEnvironmentRef, appReference, request.subjectReference).singleOrNull()
            ?: return SnapshotRepairResult(SnapshotRepairOutcome.NO_LOCAL_BINDING)

        if (mapping.second != item.activationId) {
            return SnapshotRepairResult(SnapshotRepairOutcome.RECONCILIATION_REQUIRED)
        }

        val lifecycle = jdbc.query("""select lifecycle from ums_profile_bindings
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            { rs, _ -> rs.getString(1) },
            source.issuerEnvironmentRef, appReference, mapping.first).singleOrNull()
            ?: return SnapshotRepairResult(SnapshotRepairOutcome.NO_LOCAL_BINDING)
        when (lifecycle) {
            "ERASED" -> return SnapshotRepairResult(SnapshotRepairOutcome.REFUSED_ERASED)
            "CLOSED", "DELETION_PENDING" -> return SnapshotRepairResult(SnapshotRepairOutcome.REFUSED_RESTRICTED)
        }

        val producer = "iam:${source.issuerEnvironmentRef}"
        val stream = "iam-onboarding:${mapping.first}"
        val checkpoint = jdbc.query("""select source_revision from ums_input_checkpoints
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            and producer = ? and stream_id = ?""",
            { rs, _ -> rs.getLong(1) }, source.issuerEnvironmentRef, appReference, mapping.first,
            producer, stream).singleOrNull() ?: 0L
        if (item.onboardingVersion <= checkpoint) {
            return SnapshotRepairResult(SnapshotRepairOutcome.NO_CHANGE, checkpoint)
        }

        jdbc.update("""insert into ums_input_checkpoints
            (issuer_environment_ref, app_reference, subject_reference, producer, stream_id, source_revision)
            values (?, ?, ?, ?, ?, ?)
            on conflict (issuer_environment_ref, app_reference, subject_reference, producer, stream_id)
            do update set source_revision = excluded.source_revision""",
            source.issuerEnvironmentRef, appReference, mapping.first, producer, stream, item.onboardingVersion)
        return SnapshotRepairResult(SnapshotRepairOutcome.CONVERGED, item.onboardingVersion)
    }

    /** Returns null when the page has no ACTIVE item for the subject. */
    private fun parse(json: String, applicationId: String, subject: String): SnapshotItem? {
        val root = mapper.readTree(json)
        requireObject(root, setOf("data"))
        val data = root.get("data") ?: throw IllegalArgumentException("Snapshot data is missing")
        requireObject(data, setOf("appRegistrationId", "items", "hasMore", "nextCursor"))
        val pageApp = text(data, "appRegistrationId", APP_ID)
        require(pageApp == applicationId) { "Snapshot application does not match the authenticated binding" }
        val hasMore = data.get("hasMore")?.takeIf { it.isBoolean }?.booleanValue()
            ?: throw IllegalArgumentException("hasMore is missing")
        if (hasMore) {
            // A truncated page cannot be treated as an absence of evidence.
            throw IllegalArgumentException("A partial snapshot page cannot repair a binding")
        }
        val items = data.get("items")?.takeIf { it.isArray } ?: throw IllegalArgumentException("items is missing")
        require(items.size() <= 200) { "Snapshot page is oversized" }
        // Validate every item, not only the one requested: a malformed page is not evidence.
        val parsed = items.map { node ->
            requireObject(node, setOf("appSubject", "activationId", "activationState", "activationVersion",
                "onboardingReason", "onboardingVersion", "requiredProfileState"))
            val itemSubject = text(node, "appSubject", SUBJECT)
            val activationId = text(node, "activationId", ACTIVATION)
            require(text(node, "activationState", Regex("ACTIVE")) == "ACTIVE")
            require(text(node, "requiredProfileState", Regex("PROFILE_REQUIRED")) == "PROFILE_REQUIRED")
            require(text(node, "onboardingReason", Regex("FIRST_ACTIVATION|RECONCILIATION")) in
                setOf("FIRST_ACTIVATION", "RECONCILIATION"))
            positive(node, "activationVersion")
            SnapshotItem(itemSubject, activationId, positive(node, "onboardingVersion"))
        }
        require(parsed.map { it.subject }.toSet().size == parsed.size) { "Snapshot page repeats a subject" }
        return parsed.singleOrNull { it.subject == subject }
    }

    private fun requireObject(node: JsonNode?, allowed: Set<String>) {
        require(node != null && node.isObject) { "Snapshot object is malformed" }
        val names = node.fieldNames().asSequence().toSet()
        require(names.all { it in allowed }) { "Snapshot contains an undeclared field" }
    }

    private fun text(node: JsonNode, field: String, pattern: Regex): String {
        val value = node.get(field)
        require(value != null && value.isTextual && value.textValue().matches(pattern)) {
            "Snapshot field is malformed"
        }
        return value.textValue()
    }

    private fun positive(node: JsonNode, field: String): Long {
        val value = node.get(field)
        require(value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() > 0) {
            "Snapshot number is malformed"
        }
        return value.longValue()
    }

    private fun decode(body: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    }.getOrNull()

    private data class SnapshotItem(val subject: String, val activationId: String, val onboardingVersion: Long)

    private companion object {
        val APP_ID = Regex("[a-z][a-z0-9-]{2,63}")
        val SUBJECT = Regex("aps_[A-Za-z0-9_-]{22,128}")
        val ACTIVATION = Regex("act_[A-Za-z0-9_-]{16,128}")
    }
}
