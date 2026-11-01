package com.vik.ums.profiles

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

internal class ProfileFieldsPersistence(private val jdbc: JdbcTemplate) {
    fun fields(scope: ProfileScope): Map<ProfileField, String> = jdbc.query("""select display_name, avatar_reference,
        display_locale, display_timezone from ums_profile_bindings where issuer_environment_ref = ?
        and app_reference = ? and subject_reference = ?""", { rs, _ ->
            ProfileField.entries.mapIndexedNotNull { index, field -> rs.getString(index + 1)?.let { field to it } }.toMap()
        }, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference).single()

    fun scope(app: AppScope, reference: UUID): ProfileScope? = jdbc.query("""select subject_reference
        from ums_profile_bindings where issuer_environment_ref = ? and app_reference = ? and profile_reference = ?""",
        { rs, _ -> ProfileScope(app.issuerEnvironmentRef, app.appReference, rs.getObject(1, UUID::class.java)) },
        app.issuerEnvironmentRef, app.appReference, reference).singleOrNull()

    fun update(scope: ProfileScope, fields: Map<ProfileField, String>, readiness: Readiness) {
        jdbc.update("""update ums_profile_bindings set display_name = ?, avatar_reference = ?, display_locale = ?,
            display_timezone = ?, readiness = ?, profile_revision = profile_revision + 1,
            binding_revision = binding_revision + 1 where issuer_environment_ref = ? and app_reference = ? and subject_reference = ?""",
            fields[ProfileField.DISPLAY_NAME], fields[ProfileField.AVATAR_REFERENCE], fields[ProfileField.DISPLAY_LOCALE],
            fields[ProfileField.DISPLAY_TIMEZONE], readiness.name, scope.issuerEnvironmentRef, scope.appReference, scope.subjectReference)
    }

    fun receipt(authority: TrustedHumanAuthority, command: ProfilePatchCommand, fingerprint: String): PatchOutcome? =
        jdbc.query("""select input_fingerprint, profile_reference, applied_revision from ums_profile_patch_receipts
            where issuer_environment_ref = ? and app_reference = ? and subject_reference = ? and actor_reference = ?
            and operation = 'PROFILE_PATCH' and idempotency_key = ?""", { rs, _ ->
            if (rs.getString(1) != fingerprint) throw ProfileIdempotencyConflictException()
            PatchOutcome(rs.getObject(2, UUID::class.java), rs.getLong(3).toString())
        }, authority.scope.issuerEnvironmentRef, authority.scope.appReference, authority.scope.subjectReference,
            authority.actorReference, command.idempotencyKey).singleOrNull()

    fun saveReceipt(authority: TrustedHumanAuthority, command: ProfilePatchCommand, fingerprint: String, outcome: PatchOutcome) {
        jdbc.update("""insert into ums_profile_patch_receipts
            (issuer_environment_ref, app_reference, subject_reference, actor_reference, operation, idempotency_key,
            input_fingerprint, profile_reference, applied_revision) values (?, ?, ?, ?, 'PROFILE_PATCH', ?, ?, ?, ?)""",
            authority.scope.issuerEnvironmentRef, authority.scope.appReference, authority.scope.subjectReference,
            authority.actorReference, command.idempotencyKey, fingerprint, outcome.profileReference, outcome.appliedRevision.toLong())
    }
}
