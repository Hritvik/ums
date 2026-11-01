package com.vik.ums.profiles

import com.vik.ums.iam.CallerContext
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Maps an opaque IAM caller reference to UMS scope references. A missing mapping is treated as
 * an unavailable target, never as permission to create or guess one.
 */
class ProfileScopeResolver(
    private val jdbc: JdbcTemplate,
    private val issuerEnvironmentRef: UUID,
) {
    fun resolve(caller: CallerContext): ProfileScope? {
        val appReference = jdbc.query("""select app_reference from ums_iam_app_mappings
            where issuer_environment_ref = ? and app_registration_id = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            issuerEnvironmentRef, caller.appId).singleOrNull() ?: return null
        val subjectReference = jdbc.query("""select subject_reference from ums_iam_subject_mappings
            where issuer_environment_ref = ? and app_reference = ? and app_subject = ?""",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            issuerEnvironmentRef, appReference, caller.subjectReference).singleOrNull() ?: return null
        return ProfileScope(issuerEnvironmentRef, appReference, subjectReference)
    }
}
