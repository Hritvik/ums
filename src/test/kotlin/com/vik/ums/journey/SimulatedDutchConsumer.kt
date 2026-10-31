package com.vik.ums.journey

import com.fasterxml.jackson.databind.JsonNode
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import java.security.MessageDigest
import java.util.UUID

/** Single-binding, in-memory Dutch simulation. Not production consumer, relay, snapshot authority, or Member contract. */
internal class SimulatedDutchConsumer(private val app: AppScope, private val environment: String) {
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().apply { enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION) }
    private var scope: ProfileScope? = null
    private var member: UUID? = null
    private var appliedRevision = 0L
    private var gapThrough = 0L
    private var ready = false
    private var ledgerPermission = false
    private var seenEvents: Map<UUID, String> = emptyMap()
    private var revisionContent: Map<Long, String> = emptyMap()
    private var appliedEvents: Set<UUID> = emptySet()

    fun deliver(json: String): String {
        val event = mapper.readTree(json)
        exact(event, "eventId", "schemaVersion", "producer", "environment", "occurredAt", "correlationId",
            "causationId", "type", "state", "reason")
        require(event["type"].asText() == "ums.profile.state-changed.v1" && event["schemaVersion"].asText() == "1")
        val state = event["state"]
        val incomingScope = mapper.treeToValue(state["scope"], ProfileScope::class.java)
        if (event["producer"].asText() != "ums" || event["environment"].asText() != environment ||
            incomingScope.application() != app || scope != null && scope != incomingScope) return "UNAVAILABLE"
        val revision = revision(state, "bindingRevision")
        val eventId = UUID.fromString(event["eventId"].asText())
        val fingerprint = fingerprint(event)
        val content = fingerprint(mapper.valueToTree<JsonNode>(mapOf("state" to state, "reason" to event["reason"].asText())))
        require(seenEvents[eventId] == null || seenEvents[eventId] == fingerprint) { "Event identity content collision" }
        require(revisionContent[revision] == null || revisionContent[revision] == content) { "Binding revision content collision" }
        scope = incomingScope
        seenEvents = seenEvents + (eventId to fingerprint)
        revisionContent = revisionContent + (revision to content)
        if (eventId in appliedEvents) return "DUPLICATE"
        if (revision <= appliedRevision) return "STALE"
        if (revision != appliedRevision + 1) {
            gapThrough = maxOf(gapThrough, revision)
            return "REPAIR_REQUIRED"
        }
        if (member == null) member = UUID.randomUUID()
        appliedRevision = revision
        appliedEvents = appliedEvents + eventId
        ready = state["lifecycle"].asText() == "ACTIVE" && state["readiness"].asText() == "PROFILE_READY"
        return "APPLIED"
    }

    fun state(): String = when {
        gapThrough > appliedRevision -> "REPAIR_REQUIRED"
        member == null -> "WAIT"
        ready -> "READY"
        else -> "COMPLETE_PROFILE"
    }
    fun memberReference(): UUID? = member
    fun canUseLedger(): Boolean = ledgerPermission && state() == "READY"
    fun grantLedgerPermission(reference: UUID) {
        require(reference == member) { "Dutch Member authority is required" }
        ledgerPermission = true
    }

    private fun exact(node: JsonNode, vararg fields: String) {
        require(node.isObject && node.fieldNames().asSequence().toSet() == fields.toSet())
    }
    private fun revision(state: JsonNode, field: String): Long {
        val value = state[field]
        require(value.isTextual && value.asText().matches(Regex("[1-9][0-9]{0,18}")))
        return requireNotNull(value.asText().toLongOrNull()).also { require(it > 0) }
    }
    private fun fingerprint(node: JsonNode): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(canonical(node))).joinToString("") { "%02x".format(it) }
    private fun canonical(node: JsonNode): Any? = when {
        node.isObject -> node.fieldNames().asSequence().sorted().associateWith { canonical(node[it]) }
        node.isArray -> node.map(::canonical)
        node.isNull -> null
        else -> node.asText()
    }
}
