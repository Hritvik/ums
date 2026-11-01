package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.lifecycle.LifecyclePersistence
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.sql.Timestamp
import java.time.Clock
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

    /** A complete page has no ACTIVE item for the subject. */
    NO_EVIDENCE,

    /** The page is truncated and does not contain the subject; absence is not evidence. */
    PAGE_INCOMPLETE,

    /** The local checkpoint is current; nothing changed. */
    NO_CHANGE,

    /** The local ordering checkpoint was advanced to the snapshot version. */
    CHECKPOINT_CONVERGED,

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
 * Repairs the ordering of one app-scoped binding from the accepted IAM onboarding snapshot.
 *
 * The snapshot is authoritative for an ACTIVE onboarding version, not for identity, deletion,
 * closure, or profile fields. It may advance a lagging checkpoint under the shared application
 * lock; it never creates a profile, restores an erased one, clears a cause, or reopens a closed
 * profile. Pinned activation disagreement is reported for reconciliation.
 */
class IamSnapshotRepairService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val source: TrustedIamSource,
    private val registeredProducer: String,
    private val maximumBytes: Int = 256 * 1024,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val policyMapper = jacksonObjectMapper()
    private val lifecycle = LifecyclePersistence(jdbc)
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
        val page = runCatching { parse(json, request.applicationId, request.subjectReference) }
            .getOrElse { return SnapshotRepairResult(SnapshotRepairOutcome.REJECTED) }

        return transactions.execute {
            val appReference = jdbc.query("""select app_reference from ums_iam_app_mappings
                where issuer_environment_ref = ? and app_registration_id = ?""",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                source.issuerEnvironmentRef, request.applicationId).singleOrNull()
                ?: return@execute SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)
            // Every other mutation serialises on this row; repair must not race closure or erasure.
            lockedPolicy(jdbc, policyMapper, AppScope(source.issuerEnvironmentRef, appReference))
                ?: return@execute SnapshotRepairResult(SnapshotRepairOutcome.REJECTED)

            val item = page.item
                ?: return@execute SnapshotRepairResult(
                    if (page.complete) SnapshotRepairOutcome.NO_EVIDENCE else SnapshotRepairOutcome.PAGE_INCOMPLETE)

            val mapping = jdbc.query("""select subject_reference, activation_id from ums_iam_subject_mappings
                where issuer_environment_ref = ? and app_reference = ? and app_subject = ?""",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
                source.issuerEnvironmentRef, appReference, request.subjectReference).singleOrNull()
                ?: return@execute SnapshotRepairResult(SnapshotRepairOutcome.NO_LOCAL_BINDING)

            if (mapping.second != item.activationId) {
                return@execute SnapshotRepairResult(SnapshotRepairOutcome.RECONCILIATION_REQUIRED)
            }

            val bindingRevision = jdbc.query("""select binding_revision from ums_profile_bindings
                where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
                { rs, _ -> rs.getLong(1) },
                source.issuerEnvironmentRef, appReference, mapping.first).singleOrNull()
                ?: return@execute SnapshotRepairResult(SnapshotRepairOutcome.NO_LOCAL_BINDING)
            val lifecycleState = jdbc.query("""select lifecycle from ums_profile_bindings
                where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
                { rs, _ -> rs.getString(1) },
                source.issuerEnvironmentRef, appReference, mapping.first).singleOrNull()
                ?: return@execute SnapshotRepairResult(SnapshotRepairOutcome.NO_LOCAL_BINDING)
            when (lifecycleState) {
                "ERASED" -> return@execute SnapshotRepairResult(SnapshotRepairOutcome.REFUSED_ERASED)
                "CLOSED", "DELETION_PENDING" ->
                    return@execute SnapshotRepairResult(SnapshotRepairOutcome.REFUSED_RESTRICTED)
            }

            val producer = IamOnboardingIdentity.producer(source.issuerEnvironmentRef)
            val stream = IamOnboardingIdentity.stream(mapping.first)
            val checkpoint = jdbc.query("""select source_revision from ums_input_checkpoints
                where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
                and producer = ? and stream_id = ?""",
                { rs, _ -> rs.getLong(1) }, source.issuerEnvironmentRef, appReference, mapping.first,
                producer, stream).singleOrNull() ?: 0L
            if (item.onboardingVersion <= checkpoint) {
                return@execute SnapshotRepairResult(SnapshotRepairOutcome.NO_CHANGE, checkpoint)
            }

            // Monotonic even if two repairs race: the larger version wins.
            jdbc.update("""insert into ums_input_checkpoints
                (issuer_environment_ref, app_reference, subject_reference, producer, stream_id, source_revision)
                values (?, ?, ?, ?, ?, ?)
                on conflict (issuer_environment_ref, app_reference, subject_reference, producer, stream_id)
                do update set source_revision = excluded.source_revision
                where ums_input_checkpoints.source_revision < excluded.source_revision""",
                source.issuerEnvironmentRef, appReference, mapping.first, producer, stream, item.onboardingVersion)
            val now = clock.instant()
            lifecycle.recordEvidence(ProfileScope(source.issuerEnvironmentRef, appReference, mapping.first),
                "SNAPSHOT_RECONCILED", bindingRevision, now, null, now, UUID.randomUUID(), request.correlationId)
            SnapshotRepairResult(SnapshotRepairOutcome.CHECKPOINT_CONVERGED, item.onboardingVersion)
        }!!
    }

    /** A page either contains the subject or is complete; absence from a truncated page is not evidence. */
    private fun parse(json: String, applicationId: String, subject: String): SnapshotPage {
        val root = mapper.readTree(json)
        requireObject(root, setOf("data"))
        val data = root.get("data") ?: throw IllegalArgumentException("Snapshot data is missing")
        requireObject(data, setOf("appRegistrationId", "items", "hasMore", "nextCursor"))
        val pageApp = text(data, "appRegistrationId", APP_ID)
        require(pageApp == applicationId) { "Snapshot application does not match the authenticated binding" }
        val hasMore = data.get("hasMore")?.takeIf { it.isBoolean }?.booleanValue()
            ?: throw IllegalArgumentException("hasMore is missing")
        data.get("nextCursor")?.let {
            require(it.isTextual && it.textValue().length in 1..512) { "nextCursor is malformed" }
        }
        val items = data.get("items")?.takeIf { it.isArray } ?: throw IllegalArgumentException("items is missing")
        require(items.size() <= 200) { "Snapshot page is oversized" }
        // Validate every item, not only the requested one: a malformed page is not evidence.
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
        return SnapshotPage(parsed.singleOrNull { it.subject == subject }, !hasMore)
    }

    private fun requireObject(node: JsonNode?, allowed: Set<String>) {
        require(node != null && node.isObject) { "Snapshot object is malformed" }
        require(node.fieldNames().asSequence().all { it in allowed }) { "Snapshot contains an undeclared field" }
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
    private data class SnapshotPage(val item: SnapshotItem?, val complete: Boolean)

    private companion object {
        val APP_ID = Regex("[a-z][a-z0-9-]{2,63}")
        val SUBJECT = Regex("aps_[A-Za-z0-9_-]{22,128}")
        val ACTIVATION = Regex("act_[A-Za-z0-9_-]{16,128}")
    }
}
