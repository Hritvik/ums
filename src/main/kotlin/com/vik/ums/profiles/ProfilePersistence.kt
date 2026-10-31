package com.vik.ums.profiles

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

internal class ProfilePersistence(private val jdbc: JdbcTemplate) {
    fun binding(scope: ProfileScope): BindingState? = jdbc.query("""select binding_revision, policy_revision, lifecycle, readiness,
        profile_reference, generation, profile_revision from ums_profile_bindings
        where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
        { rs, _ ->
            val lifecycle = Lifecycle.valueOf(rs.getString("lifecycle"))
            val erased = lifecycle == Lifecycle.ERASED
            BindingState(scope, rs.getLong("binding_revision").toString(), rs.getLong("policy_revision").toString(),
                lifecycle, Readiness.valueOf(rs.getString("readiness")),
                if (erased) null else rs.getObject("profile_reference", UUID::class.java),
                if (erased) null else rs.getLong("generation").toString(),
                if (erased) null else rs.getLong("profile_revision").toString(), causes(scope))
        }, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).singleOrNull()

    private fun causes(scope: ProfileScope): DeletionCauses {
        val causes = jdbc.query("""select cause_type, request_reference, source_revision, accepted_at, deadline
            from ums_profile_deletion_causes where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            { rs, _ -> rs.getString(1) to DeletionCause(rs.getObject(2, UUID::class.java), rs.getLong(3).toString(),
                rs.getTimestamp(4).toInstant().toString(), rs.getTimestamp(5).toInstant().toString()) },
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).toMap()
        return DeletionCauses(causes["APP_ONLY"], causes["IAM_GLOBAL"])
    }

    fun create(scope: ProfileScope, policy: AppProfilePolicy): BindingState {
        val readiness = if (policy.requiredFields.isEmpty()) Readiness.PROFILE_READY else Readiness.PROFILE_REQUIRED
        val reference = UUID.randomUUID()
        jdbc.update("""insert into ums_profile_bindings
            (issuer_environment_ref, app_reference, subject_reference, profile_reference,
             binding_revision, policy_revision, generation, profile_revision, lifecycle, readiness)
            values (?, ?, ?, ?, 1, ?, 1, 1, 'ACTIVE', ?)""",
            scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference, reference,
            policy.policyRevision, readiness.name)
        return BindingState(scope, "1", policy.policyRevision.toString(), Lifecycle.ACTIVE, readiness, reference, "1", "1")
    }

    fun checkpoint(fact: TrustedOnboardingFact): Long = jdbc.query("""select source_revision
        from ums_input_checkpoints where issuer_environment_ref = ? and app_reference = ?
        and subject_reference = ? and producer = ? and stream_id = ?""",
        { rs, _ -> rs.getLong(1) }, fact.scope.issuerEnvironmentRef, fact.scope.appReference,
        fact.scope.subjectReference, fact.producer, fact.streamId).singleOrNull() ?: 0

    fun recordReceipt(fact: TrustedOnboardingFact, fingerprint: String) {
        jdbc.update("""insert into ums_event_inbox
            (producer, event_id, application_id, stream_id, stream_revision, input_fingerprint)
            values (?, ?, ?, ?, ?, ?)""", fact.producer, fact.eventId,
            fact.scope.appReference.toString(), fact.streamId, fact.sourceRevision, fingerprint)
    }

    fun advanceCheckpoint(fact: TrustedOnboardingFact) {
        jdbc.update("""insert into ums_input_checkpoints
            (issuer_environment_ref, app_reference, subject_reference, producer, stream_id, source_revision)
            values (?, ?, ?, ?, ?, ?) on conflict
            (issuer_environment_ref, app_reference, subject_reference, producer, stream_id)
            do update set source_revision = excluded.source_revision""",
            fact.scope.issuerEnvironmentRef, fact.scope.appReference, fact.scope.subjectReference,
            fact.producer, fact.streamId, fact.sourceRevision)
    }

    fun fingerprint(producer: String, eventId: UUID): String? = jdbc.query(
        "select input_fingerprint from ums_event_inbox where producer = ? and event_id = ?",
        { rs, _ -> rs.getString(1) ?: "legacy-untrusted-event" }, producer, eventId).singleOrNull()
}
