package com.vik.ums.iam

import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileScope
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

internal class IamOnboardingPersistence(private val jdbc: JdbcTemplate, private val source: TrustedIamSource) {
    fun bindSourceAndApp(app: AppScope, registration: String) {
        jdbc.update("insert into ums_iam_source_bindings values (?, ?) on conflict do nothing", source.issuerEnvironmentRef, source.environment)
        val environment = jdbc.queryForObject("select environment from ums_iam_source_bindings where issuer_environment_ref = ? for update",
            String::class.java, source.issuerEnvironmentRef)
        require(environment == source.environment) { "IAM source environment binding changed" }
        jdbc.update("insert into ums_iam_app_mappings values (?, ?, ?) on conflict do nothing", source.issuerEnvironmentRef,
            registration, app.appReference)
        val reference = jdbc.queryForObject("""select app_reference from ums_iam_app_mappings
            where issuer_environment_ref = ? and app_registration_id = ?""", UUID::class.java, source.issuerEnvironmentRef, registration)
        require(reference == app.appReference) { "IAM registration binding changed" }
    }

    fun knownReceipt(event: IamOnboardingEnvelope): UUID? = jdbc.query("""select internal_event_id, input_fingerprint
        from ums_iam_onboarding_receipts where issuer_environment_ref = ? and environment = ?
        and producer = 'iam' and original_event_id = ?""", { rs, _ ->
        require(rs.getString(2) == event.fingerprint) { "IAM event identity was reused with different content" }
        rs.getObject(1, UUID::class.java)
    }, source.issuerEnvironmentRef, source.environment, event.eventId).singleOrNull()

    fun receipt(event: IamOnboardingEnvelope, app: AppScope): UUID {
        val previous = knownReceipt(event)
        if (previous != null) return previous
        jdbc.update("""insert into ums_iam_activation_owners values (?, ?, ?, ?) on conflict do nothing""",
            source.issuerEnvironmentRef, app.appReference, event.activationId, event.appSubject)
        val owner = jdbc.queryForObject("""select app_subject from ums_iam_activation_owners
            where issuer_environment_ref = ? and app_reference = ? and activation_id = ?""", String::class.java,
            source.issuerEnvironmentRef, app.appReference, event.activationId)
        require(owner == event.appSubject) { "IAM activation aggregate owner changed" }
        val priorPayloads = jdbc.query("""select payload_fingerprint from ums_iam_onboarding_receipts
            where issuer_environment_ref = ? and app_reference = ? and activation_id = ? and onboarding_version = ?""",
            { rs, _ -> rs.getString(1) }, source.issuerEnvironmentRef, app.appReference,
            event.activationId, event.onboardingVersion)
        require(priorPayloads.all { it == event.payloadFingerprint }) { "IAM aggregate revision has conflicting content" }
        val reference = UUID.randomUUID()
        jdbc.update("""insert into ums_iam_onboarding_receipts
            (issuer_environment_ref, environment, producer, original_event_id, internal_event_id, app_reference,
            app_registration_id, app_subject, activation_id, activation_version, onboarding_version, onboarding_reason,
            input_fingerprint, payload_fingerprint) values (?, ?, 'iam', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            source.issuerEnvironmentRef, source.environment, event.eventId, reference, app.appReference,
            event.appRegistrationId, event.appSubject, event.activationId, event.activationVersion,
            event.onboardingVersion, event.reason, event.fingerprint, event.payloadFingerprint)
        return reference
    }

    fun subject(app: AppScope, event: IamOnboardingEnvelope): Pair<ProfileScope, String> {
        jdbc.update("""insert into ums_iam_subject_mappings
            (issuer_environment_ref, app_reference, app_subject, subject_reference, activation_id)
            values (?, ?, ?, ?, ?) on conflict do nothing""", app.issuerEnvironmentRef, app.appReference,
            event.appSubject, UUID.randomUUID(), event.activationId)
        return jdbc.query("""select subject_reference, activation_id from ums_iam_subject_mappings
            where issuer_environment_ref = ? and app_reference = ? and app_subject = ?""", { rs, _ ->
            ProfileScope(app.issuerEnvironmentRef, app.appReference, rs.getObject(1, UUID::class.java)) to rs.getString(2)
        }, app.issuerEnvironmentRef, app.appReference, event.appSubject).single()
    }
}
