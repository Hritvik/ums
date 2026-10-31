package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Configuration authority only; never expose this seam as user-controlled adoption. */
class ProfilePolicyRegistry(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    fun register(policy: AppProfilePolicy) {
        val encoded = mapper.writeValueAsString(policy)
        transactions.executeWithoutResult {
            jdbc.update("""insert into ums_profile_policies (issuer_environment_ref, app_reference, policy)
                values (?, ?, ?::jsonb) on conflict do nothing""",
                policy.app.issuerEnvironmentRef, policy.app.appReference, encoded)
            val current = lockedPolicy(jdbc, mapper, policy.app)
            check(current == policy) { "Policy changes require explicit reevaluation, which is not implemented" }
        }
    }
}

internal fun lockedPolicy(jdbc: JdbcTemplate, mapper: ObjectMapper, app: AppScope): AppProfilePolicy? =
    jdbc.query("""select policy::text from ums_profile_policies
        where issuer_environment_ref = ? and app_reference = ? for update""",
        { rs, _ -> mapper.readValue<AppProfilePolicy>(rs.getString(1)) },
        app.issuerEnvironmentRef, app.appReference).singleOrNull()
