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
        // A display-only edit must stay silent even after retention removed the last published row.
        if (reason == "FIELDS_CHANGED" && unchanged(state)) return
        val retiredGeneration = if (state.lifecycle == Lifecycle.ERASED) {
            retiredGeneration(state) ?: journalGeneration(state)
        } else null
        if (state.lifecycle == Lifecycle.ERASED && retiredGeneration == null) {
            log.warn("No retired generation available for an ERASED report in app {}", state.scope.appReference)
            return
        }
        val safeCorrelation = correlationId.takeIf { ENVELOPE_ID.matches(it) } ?: UUID.randomUUID().toString()
        val contentObligation = if (state.lifecycle == Lifecycle.ERASED) contentObligation(state) else false

        val eventId = UUID.randomUUID()
        val payload = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "eventId" to "evt_" + eventId.toString().replace("-", ""),
            "eventType" to EVENT_TYPE,
            "producer" to "ums",
            "environment" to environment,
            "occurredAt" to clock.instant().toString(),
            "correlationId" to safeCorrelation,
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
                reasonFor(reason, state.lifecycle)?.let { put("lifecycleReason", it) }
                if (state.lifecycle == Lifecycle.ERASED) {
                    put("retiredGeneration", retiredGeneration!!)
                } else {
                    put("generation", state.generation!!.toLong())
                    put("profileRevision", state.profileRevision!!)
                }
                put("contentObligation", contentObligation)
            },
        )
        jdbc.update("""insert into ums_event_outbox
            (event_id, application_id, stream_id, stream_revision, event_type, payload, occurred_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?)
            on conflict (application_id, stream_id, stream_revision) do nothing""",
            eventId,
            state.scope.appReference.toString(), stream, state.bindingRevision.toLong(), EVENT_TYPE,
            mapper.writeValueAsString(payload), Timestamp.from(clock.instant()))
        remember(state)
    }

    private fun mapping(scope: ProfileScope): Pair<String, String>? =
        jdbc.query("""select s.app_subject, a.app_registration_id
            from ums_iam_subject_mappings s join ums_iam_app_mappings a
              on a.issuer_environment_ref = s.issuer_environment_ref
             and a.app_reference = s.app_reference
            where s.issuer_environment_ref = ? and s.app_reference = ? and s.subject_reference = ?""",
            { rs: ResultSet, _: Int -> rs.getString(1) to rs.getString(2) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()

    /** Last reported state kept in the database, so an outbox sweep cannot make a no-op report. */
    private fun unchanged(state: BindingState): Boolean =
        jdbc.query("""select readiness, lifecycle from ums_profile_association_state
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            { rs: ResultSet, _: Int -> rs.getString(1) to rs.getString(2) },
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference)
            .singleOrNull() == (state.readiness.name to state.lifecycle.name)

    private fun remember(state: BindingState) {
        jdbc.update("""insert into ums_profile_association_state
            (issuer_environment_ref, app_reference, subject_reference, readiness, lifecycle, updated_at)
            values (?, ?, ?, ?, ?, current_timestamp)
            on conflict (issuer_environment_ref, app_reference, subject_reference) do update
                set readiness = excluded.readiness, lifecycle = excluded.lifecycle,
                    updated_at = excluded.updated_at""",
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference,
            state.readiness.name, state.lifecycle.name)
    }

    private fun retiredGeneration(state: BindingState): Long? =
        jdbc.query("""select retired_generation from ums_profile_erasure_obligations
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            order by erased_at desc limit 1""",
            { rs: ResultSet, _: Int -> rs.getLong(1) },
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference)
            .singleOrNull()

    /** The journal feed is the fallback tombstone handle when the obligation row is unavailable. */
    private fun journalGeneration(state: BindingState): Long? =
        jdbc.query("""select retired_generation from ums_erasure_journal_feed
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            order by erased_at desc limit 1""",
            { rs: ResultSet, _: Int -> rs.getLong(1) },
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference)
            .singleOrNull()

    private fun contentObligation(state: BindingState): Boolean =
        jdbc.query("""select content_obligation from ums_profile_erasure_obligations
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?
            order by erased_at desc limit 1""",
            { rs: ResultSet, _: Int -> rs.getBoolean(1) },
            state.scope.issuerEnvironmentRef, state.scope.appReference, state.scope.subjectReference)
            .singleOrNull() ?: false

    /**
     * A reason describes the transition, not always the resulting state: a cancellation while a
     * global cause is still live leaves the profile restricted, so it must not be reported active.
     */
    private fun reasonFor(reason: String, lifecycle: Lifecycle): String? = when (reason) {
        "DELETION_REQUESTED" -> "DELETION_REQUESTED".takeIf { lifecycle == Lifecycle.DELETION_PENDING }
        "DELETION_CANCELLED" -> "DELETION_CANCELLED".takeIf { lifecycle == Lifecycle.ACTIVE }
        "CLOSED" -> "CLOSED".takeIf { lifecycle == Lifecycle.CLOSED }
        "ERASED" -> "ERASED".takeIf { lifecycle == Lifecycle.ERASED }
        "REACTIVATED" -> "REOPENED".takeIf { lifecycle == Lifecycle.ACTIVE }
        else -> null
    }

    private companion object {
        const val EVENT_TYPE = "ums.profile.association-report.v1"
        val APP_SUBJECT = Regex("^aps_[A-Za-z0-9_-]{19,128}$")
        val REGISTRATION = Regex("^[a-z][a-z0-9-]{2,63}$")
        val ENVELOPE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val log = LoggerFactory.getLogger(AssociationReports::class.java)
    }
}
