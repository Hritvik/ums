package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Publishes the correlation report the Dutch owner accepted: the app-scoped profile state keyed
 * by the same app registration and app subject Dutch uses. It carries only lifecycle facts and
 * references, never display values.
 */
internal class AssociationReports(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val clock: Clock,
) {
    fun emit(state: BindingState, reason: String, causationId: UUID, correlationId: String) {
        val mapping = mapping(state.scope) ?: run {
            log.warn("No app subject mapping for a profile in app {}", state.scope.appReference)
            return
        }
        val (appSubject, registrationId) = mapping
        if (!APP_SUBJECT.matches(appSubject) || !REGISTRATION.matches(registrationId)) {
            log.warn("App subject or registration is not contract-shaped for app {}", state.scope.appReference)
            return
        }
        val stream = "${state.scope.issuerEnvironmentRef}:${state.scope.appReference}:${state.scope.subjectReference}:association"
        if (reason == "FIELDS_CHANGED" && unchanged(stream, state)) return
        val retiredGeneration = if (state.lifecycle == Lifecycle.ERASED) retiredGeneration(state) else null
        if (state.lifecycle == Lifecycle.ERASED && retiredGeneration == null) {
            log.warn("No erasure obligation found for an ERASED report in app {}", state.scope.appReference)
            return
        }

        val eventId = UUID.randomUUID()
        val payload = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "eventId" to "evt_" + eventId.toString().replace("-", ""),
            "eventType" to EVENT_TYPE,
            "producer" to "ums",
            "environment" to environment,
            "occurredAt" to clock.instant().toString(),
            "correlationId" to correlationId,
            "causationId" to causationId.toString(),
            "aggregate" to mapOf(
                "type" to "UMS_PROFILE_BINDING",
                "id" to appSubject,
                "version" to state.bindingRevision.toLong(),
            ),
            "destination" to mapOf("appRegistrationId" to registrationId),
            "payload" to buildMap {
                put("appSubject", appSubject)
                put("profileState", state.readiness.name)
                put("lifecycleState", state.lifecycle.name)
                reasonFor(reason)?.let { put("lifecycleReason", it) }
                if (state.lifecycle == Lifecycle.ERASED) {
                    put("retiredGeneration", retiredGeneration!!)
                } else {
                    put("generation", state.generation!!.toLong())
                    put("profileRevision", state.profileRevision!!)
                }
                // Erasure only proceeds while the avatar association is absent, so no cleanup
                // obligation can exist for a report emitted today.
                put("contentObligation", false)
            },
        )
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?)
            on conflict (application_id, stream_id, stream_revision) do nothing""",
            eventId,
            state.scope.appReference.toString(), stream, state.bindingRevision.toLong(), EVENT_TYPE,
            mapper.writeValueAsString(payload), Timestamp.from(clock.instant()))
    }

    private fun mapping(scope: ProfileScope): Pair<String, String>? =
        jdbc.query("""select s.app_subject, a.app_registration_id
            from ums_iam_subject_mappings s join ums_iam_app_mappings a
              on a.issuer_environment_ref = s.issuer_environment_ref
             and a.app_reference = s.app_reference
            where s.issuer_environment_ref = ? and s.app_reference = ? and s.subject_reference = ?""",
            { rs: ResultSet, _: Int -> rs.getString(1) to rs.getString(2) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()

    /** Display-only edits do not report: skip when readiness and lifecycle are unchanged. */
    private fun unchanged(stream: String, state: BindingState): Boolean =
        jdbc.query("""select payload->'payload'->>'profileState', payload->'payload'->>'lifecycleState'
            from ums_event_outbox where stream_id = ? and event_type = ?
            order by stream_revision desc limit 1""",
            { rs: ResultSet, _: Int -> rs.getString(1) to rs.getString(2) }, stream, EVENT_TYPE)
            .singleOrNull() == (state.readiness.name to state.lifecycle.name)

    private fun retiredGeneration(state: BindingState): Long? =
        jdbc.query("""select retired_generation from ums_profile_erasure_obligations
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            order by erased_at desc limit 1""",
            { rs: ResultSet, _: Int -> rs.getLong(1) },
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference)
            .singleOrNull()

    private fun reasonFor(reason: String): String? = when (reason) {
        "DELETION_REQUESTED", "DELETION_CANCELLED", "CLOSED", "ERASED" -> reason
        "REACTIVATED" -> "REOPENED"
        else -> null
    }

    private companion object {
        const val EVENT_TYPE = "ums.profile.association-report.v1"
        val APP_SUBJECT = Regex("^aps_[A-Za-z0-9_-]{8,128}$")
        val REGISTRATION = Regex("^[a-z][a-z0-9-]{2,63}$")
        val log = LoggerFactory.getLogger(AssociationReports::class.java)
    }
}
