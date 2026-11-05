package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class ProfileStateEvents(
    private val jdbc: JdbcTemplate, private val mapper: ObjectMapper, private val environment: String, private val clock: Clock,
) {
    private val association = AssociationReports(jdbc, mapper, environment, clock)
    init { require(environment in setOf("development", "test", "staging", "production")) }
    fun emit(state: BindingState, reason: String, causationId: UUID, correlationId: String) {
        val eventId = UUID.randomUUID()
        val occurredAt = Instant.now(clock)
        val payload = mapper.writeValueAsString(mapOf(
            "eventId" to eventId, "schemaVersion" to "1", "producer" to "ums", "environment" to environment,
            "occurredAt" to occurredAt.toString(), "correlationId" to correlationId,
            "causationId" to causationId, "type" to "ums.profile.state-changed.v1", "state" to state, "reason" to reason,
        ))
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
            values (?, ?, ?, ?, 'ums.profile.state-changed.v1', ?::jsonb, ?)""", eventId,
            state.scope.appReference.toString(),
            "${state.scope.issuerEnvironmentRef}:${state.scope.appReference}:${state.scope.subjectReference}",
            state.bindingRevision.toLong(), payload, java.sql.Timestamp.from(occurredAt))
        association.emit(state, reason, causationId, correlationId)
    }
}
