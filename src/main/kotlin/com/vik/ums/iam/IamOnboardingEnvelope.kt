package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

data class TrustedIamSource(val issuerEnvironmentRef: UUID, val environment: String) {
    init { require(environment in setOf("development", "test", "staging", "production")) }
}

internal data class IamOnboardingEnvelope(
    val eventId: String, val appRegistrationId: String, val appSubject: String, val activationId: String,
    val activationVersion: Long, val onboardingVersion: Long, val reason: String, val correlationId: String,
    val fingerprint: String, val payloadFingerprint: String,
)

internal class IamOnboardingParser(private val source: TrustedIamSource) {
    private val mapper = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(32768).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun parse(json: String): IamOnboardingEnvelope {
        require(json.toByteArray(Charsets.UTF_8).size <= 32768) { "IAM event exceeds input bound" }
        val root = try { mapper.readTree(json) } catch (_: Exception) { throw IllegalArgumentException("Invalid IAM event JSON") }
        exact(root, "eventId", "eventType", "schemaVersion", "occurredAt", "producer", "environment", "aggregate",
            "correlationId", "causationId", "destination", "payload")
        constant(root, "eventType", "iam.app-subject.onboarding-ready.v1")
        require(version(root, "schemaVersion") == 1L)
        constant(root, "producer", "iam")
        constant(root, "environment", source.environment)
        val eventId = text(root, "eventId", "evt_[A-Za-z0-9_-]{16,128}")
        val timestamp = text(root, "occurredAt", ".{1,35}")
        require(validTimestamp(timestamp)) { "Invalid IAM event timestamp" }
        val correlation = text(root, "correlationId", "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        require(root["causationId"].isNull || root["causationId"].isTextual &&
            root["causationId"].textValue().matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
        val aggregate = root["aggregate"]
        exact(aggregate, "type", "id", "version")
        constant(aggregate, "type", "APP_ONBOARDING")
        val aggregateId = text(aggregate, "id", "act_[A-Za-z0-9_-]{16,125}")
        val aggregateVersion = version(aggregate, "version")
        val destination = root["destination"]
        exact(destination, "appRegistrationId")
        val app = text(destination, "appRegistrationId", "[a-z][a-z0-9-]{2,63}")
        val payload = root["payload"]
        exact(payload, "appRegistrationId", "appSubject", "activationId", "activationState", "activationVersion",
            "onboardingReason", "onboardingVersion", "requiredProfileState")
        require(text(payload, "appRegistrationId", "[a-z][a-z0-9-]{2,63}") == app)
        require(text(payload, "activationId", "act_[A-Za-z0-9_-]{16,125}") == aggregateId)
        constant(payload, "activationState", "ACTIVE")
        constant(payload, "requiredProfileState", "PROFILE_REQUIRED")
        val onboarding = version(payload, "onboardingVersion")
        require(onboarding == aggregateVersion)
        val reason = text(payload, "onboardingReason", "FIRST_ACTIVATION|RECONCILIATION")
        return IamOnboardingEnvelope(eventId, app, text(payload, "appSubject", "aps_[A-Za-z0-9_-]{22,128}"),
            aggregateId, version(payload, "activationVersion"), onboarding, reason, correlation, digest(root), digest(payload))
    }

    private fun validTimestamp(value: String): Boolean {
        val shape = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?([Zz]|[+-][0-9]{2}:[0-9]{2})""")
        if (!value.matches(shape)) return false
        // Provider timestamps can carry more fractional digits than java.time. Keep their
        // original text in the event fingerprint; truncate only for calendar validation.
        val calendarValue = value.replace(Regex("""\.([0-9]{9})[0-9]+""")) { ".${it.groupValues[1]}" }
        return runCatching { OffsetDateTime.parse(calendarValue).year > 0 }.getOrDefault(false)
    }

    private fun exact(node: JsonNode?, vararg names: String) {
        require(node != null && node.isObject && node.fieldNames().asSequence().toSet() == names.toSet()) { "Unexpected IAM event fields" }
    }
    private fun constant(node: JsonNode, field: String, value: String) { require(node[field].isTextual && node[field].textValue() == value) }
    private fun text(node: JsonNode, field: String, pattern: String): String {
        require(node[field].isTextual && node[field].textValue().matches(Regex(pattern))) { "Invalid IAM event field" }
        return node[field].textValue()
    }
    private fun version(node: JsonNode, field: String): Long {
        val value = node[field]
        require(value.isIntegralNumber && value.canConvertToLong() && value.longValue() > 0) { "Invalid IAM event version" }
        return value.longValue()
    }
    private fun digest(node: JsonNode): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(canonical(node))).joinToString("") { "%02x".format(it) }
    private fun canonical(node: JsonNode): Any? = when {
        node.isObject -> node.fieldNames().asSequence().sorted().associateWith { canonical(node[it]) }
        node.isNull -> null
        node.isNumber -> node.bigIntegerValue()
        else -> node.textValue()
    }
}
