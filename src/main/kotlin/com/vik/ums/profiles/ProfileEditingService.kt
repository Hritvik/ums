package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/** Internal authority-resolved seam only. No production authority, ingress, catalog or secret is supplied. */
class ProfileEditingService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    environment: String,
    private val catalogs: ProfileCatalogs,
    private val fingerprints: HmacPatchFingerprint,
    clock: Clock = Clock.systemUTC(),
) {
    private val bindings = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val validation = ProfileFieldValidation(catalogs)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)

    fun patch(authority: TrustedHumanAuthority, command: ProfilePatchCommand): PatchOutcome = transactions.execute {
        require(HumanAction.EDIT in authority.actions) { "Profile edit authority is required" }
        val policy = authorizedPolicy(authority.scope.application())
        require(command.changes.keys.all { it in policy.humanEditableFields }) { "Profile field is not editable" }
        val before = active(authority.scope)
        val changes = validation.normalize(command.changes)
        val fingerprint = fingerprint(command)
        val receipt = fields.receipt(authority, command, fingerprint)
        if (receipt != null) return@execute receipt
        check(before.profileRevision!!.toLong() == command.expectedVersion) { "Profile version conflict" }
        val oldFields = fields.fields(authority.scope)
        val newFields = (oldFields + changes).filterValues { it != null }.mapValues { it.value!! }
        val after = if (newFields == oldFields) before else {
            val readiness = if (policy.requiredFields.all { it in newFields }) Readiness.PROFILE_READY else Readiness.PROFILE_REQUIRED
            fields.update(authority.scope, newFields, readiness)
            active(authority.scope).also { events.emit(it, "FIELDS_CHANGED", command.idempotencyKey, command.correlationId) }
        }
        val outcome = PatchOutcome(after.profileReference!!, after.profileRevision!!)
        fields.saveReceipt(authority, command, fingerprint, outcome)
        outcome
    }!!

    fun display(authority: TrustedHumanAuthority): ProfileDisplay = transactions.execute {
        require(HumanAction.READ in authority.actions) { "Profile read authority is required" }
        authorizedPolicy(authority.scope.application())
        display(active(authority.scope), ProfileField.entries.toSet())
    }!!

    fun displayBatch(authority: TrustedProjectionAuthority, references: List<UUID>): DisplayReadResponse {
        val requested = references.toList()
        require(requested.isNotEmpty() && requested.size <= 100 && requested.toSet().size == requested.size)
        require(authority.purpose.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Projection purpose is required" }
        return transactions.execute {
            val policy = lockedPolicy(jdbc, mapper, authority.app)
            val adopted = policy?.adopted == true
            if (adopted) catalogs.requirePolicy(policy!!)
            DisplayReadResponse(requested.map { reference ->
                val scope = if (adopted && reference in authority.permittedTargets) fields.scope(authority.app, reference) else null
                val binding = scope?.let(bindings::binding)
                if (binding == null || binding.lifecycle != Lifecycle.ACTIVE) DisplayReadResult(reference, "UNAVAILABLE")
                else DisplayReadResult(reference, "AVAILABLE", display(binding, authority.allowedFields))
            })
        }!!
    }

    private fun authorizedPolicy(app: AppScope): AppProfilePolicy {
        val policy = lockedPolicy(jdbc, mapper, app)
        require(policy?.adopted == true) { "Profile capability is unavailable" }
        catalogs.requirePolicy(policy)
        return policy
    }

    private fun active(scope: ProfileScope): BindingState {
        val state = bindings.binding(scope)
        check(state?.lifecycle == Lifecycle.ACTIVE) { "Profile is unavailable" }
        return state
    }

    private fun display(state: BindingState, allowed: Set<ProfileField>): ProfileDisplay {
        val values = fields.fields(state.scope).filterKeys { it in allowed }
        val source = when {
            ProfileField.DISPLAY_NAME !in allowed -> "ABSENT"
            ProfileField.DISPLAY_NAME in values -> "EXPLICIT"
            else -> "GENERATED_FALLBACK"
        }
        return ProfileDisplay(state.profileReference!!, state.profileRevision!!,
            values.mapKeys { it.key.wireName }, source)
    }

    private fun fingerprint(command: ProfilePatchCommand): String = fingerprints.digest(mapper.writeValueAsBytes(
        listOf(command.expectedVersion.toString(), command.changes.entries.sortedBy { it.key.ordinal }
            .map { listOf(it.key.wireName, it.value) }),
    ))
}
