package com.vik.ums.profiles

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock

/** Internal local-development seam. No HTTP ingress or accepted IAM adapter is wired. */
class ProfileProvisioningService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val environment: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val persistence = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)
    init { require(environment.matches(Regex("[a-z0-9-]{1,32}"))) }

    fun provision(fact: TrustedOnboardingFact): ProvisioningResult = transactions.execute {
        val policy = lockedPolicy(jdbc, mapper, fact.scope.application())
        val fingerprint = fingerprint(fact)
        val existingFingerprint = persistence.fingerprint(fact.producer, fact.eventId)
        require(existingFingerprint == null || existingFingerprint == fingerprint) { "Event identity was reused with different input" }
        val current = persistence.binding(fact.scope)
        when {
            policy == null || !policy.adopted -> ProvisioningResult(ProvisioningOutcome.NOT_ADOPTED)
            current != null && current.lifecycle != Lifecycle.ACTIVE ->
                ProvisioningResult(ProvisioningOutcome.RESTRICTED)
            existingFingerprint != null -> ProvisioningResult(ProvisioningOutcome.DUPLICATE, current)
            else -> applyOrdered(fact, policy, current, fingerprint)
        }
    }!!

    /** Callers must already possess authority for this exact app and subject. Reads never create state. */
    fun status(permittedApplication: AppScope, scope: ProfileScope): ProfileStatus = transactions.execute {
        require(permittedApplication == scope.application()) { "Profile scope is outside the permitted application" }
        val policy = lockedPolicy(jdbc, mapper, scope.application())
        val state = persistence.binding(scope)
            ?: return@execute ProfileStatus(nextActions = listOf("WAIT"), readiness = Readiness.PROFILE_REQUIRED)
        if (state.lifecycle == Lifecycle.CLOSED) return@execute ProfileStatus(
            state = state, unmetFields = emptyList(), nextActions = listOf("REQUEST_REACTIVATION"),
            retentionDeadline = persistence.closureDeadline(scope)?.toString(),
        )
        if (state.lifecycle != Lifecycle.ACTIVE) return@execute ProfileStatus(
            nextActions = listOf("CONTACT_SUPPORT"), readiness = Readiness.PROFILE_REQUIRED,
            retentionDeadline = persistence.closureDeadline(scope)?.toString(),
        )
        val present = fields.fields(scope).keys
        val unmet = policy!!.requiredFields.filter { it !in present }.sortedBy { it.ordinal }
        ProfileStatus(state, unmet, if (unmet.isEmpty()) emptyList() else listOf("COMPLETE_PROFILE"))
    }!!

    private fun applyOrdered(
        fact: TrustedOnboardingFact, policy: AppProfilePolicy, current: BindingState?, fingerprint: String,
    ): ProvisioningResult {
        val checkpoint = persistence.checkpoint(fact)
        if (fact.sourceRevision <= checkpoint) {
            persistence.recordReceipt(fact, fingerprint)
            return ProvisioningResult(ProvisioningOutcome.STALE, current)
        }
        if (fact.sourceRevision != checkpoint + 1)
            return ProvisioningResult(ProvisioningOutcome.RECONCILIATION_REQUIRED, current)
        persistence.recordReceipt(fact, fingerprint)
        persistence.advanceCheckpoint(fact)
        if (current != null) return ProvisioningResult(ProvisioningOutcome.STALE, current)
        val state = persistence.create(fact.scope, policy)
        events.emit(state, "PROVISIONED", fact.eventId, fact.correlationId)
        return ProvisioningResult(ProvisioningOutcome.PROVISIONED, state)
    }

    private fun fingerprint(fact: TrustedOnboardingFact): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(fact)).joinToString("") { "%02x".format(it) }

}
