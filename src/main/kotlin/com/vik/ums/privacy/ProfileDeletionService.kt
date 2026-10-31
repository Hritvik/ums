package com.vik.ums.privacy

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.profiles.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Local purpose-bound privacy seam. No ingress or production IAM privacy authority is wired. */
class ProfileDeletionService(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate, private val mapper: ObjectMapper,
    environment: String, private val clock: Clock, private val maximumStrongAge: Duration,
) {
    private val bindings = ProfilePersistence(jdbc)
    private val fields = ProfileFieldsPersistence(jdbc)
    private val persistence = PrivacyPersistence(jdbc)
    private val events = ProfileStateEvents(jdbc, mapper, environment, clock)
    init { require(!maximumStrongAge.isNegative && !maximumStrongAge.isZero) }

    fun request(authority: TrustedPrivacyAuthority, command: DeletionRequestCommand): PrivacyAccepted = transactions.execute {
        val (_, now) = authorize(authority, PrivacyPurpose.REQUEST_DELETION)
        val before = current(authority.scope)
        val fingerprint = fingerprint(command.expectedVersion.toString())
        persistence.receipt(authority, command.idempotencyKey, fingerprint, before)?.let { return@execute it }
        check(before.bindingRevision.toLong() == command.expectedVersion) { "Binding version conflict" }
        check(before.deletionCauses.appOnly == null) { "App-only deletion is already pending" }
        val reference = UUID.randomUUID()
        val acceptedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        persistence.accept(before, reference, acceptedAt, acceptedAt.plus(Duration.ofDays(30)))
        val after = current(authority.scope)
        val result = PrivacyAccepted(reference, "1")
        persistence.record(authority, command.idempotencyKey, fingerprint, after, result, now, command.correlationId)
        events.emit(after, "DELETION_REQUESTED", command.idempotencyKey, command.correlationId)
        result
    }!!

    fun cancel(authority: TrustedPrivacyAuthority, command: DeletionCancellationCommand): PrivacyAccepted = transactions.execute {
        val (policy, now) = authorize(authority, PrivacyPurpose.CANCEL_DELETION)
        val before = current(authority.scope)
        val fingerprint = fingerprint(command.requestReference.toString(), command.expectedVersion.toString())
        persistence.receipt(authority, command.idempotencyKey, fingerprint, before)?.let { return@execute it }
        val request = persistence.request(authority.scope, command.requestReference)
        requireGeneration(before, request)
        check(before.bindingRevision.toLong() == command.expectedVersion) { "Binding version conflict" }
        check(request.status == "PENDING" && now.isBefore(request.deadline)) { "Deletion cancellation is unavailable" }
        check(before.deletionCauses.appOnly?.requestReference == request.reference) { "Deletion cause does not match request" }
        persistence.cancel(authority.scope, request.reference)
        val lifecycle = when {
            before.deletionCauses.iamGlobal != null -> Lifecycle.DELETION_PENDING
            persistence.closurePresent(authority.scope) -> Lifecycle.CLOSED
            else -> Lifecycle.ACTIVE
        }
        val presentFields = fields.fields(authority.scope).keys
        val ready = lifecycle == Lifecycle.ACTIVE && policy.requiredFields.all { it in presentFields }
        persistence.transition(authority.scope, lifecycle.name, if (ready) Readiness.PROFILE_READY.name else Readiness.PROFILE_REQUIRED.name)
        val after = current(authority.scope)
        val result = PrivacyAccepted(request.reference, Math.addExact(request.revision.toLong(), 1).toString())
        persistence.record(authority, command.idempotencyKey, fingerprint, after, result, now, command.correlationId)
        events.emit(after, "DELETION_CANCELLED", command.idempotencyKey, command.correlationId)
        result
    }!!

    fun status(authority: TrustedPrivacyAuthority, reference: UUID): DeletionRequestStatus = transactions.execute {
        val (policy, now) = authorize(authority, PrivacyPurpose.READ_DELETION_STATUS)
        val state = bindings.binding(authority.scope) ?: throw IllegalStateException("Profile is unavailable")
        val request = persistence.request(authority.scope, reference)
        if (state.lifecycle == Lifecycle.ERASED) {
            check(persistence.completedFence(authority.scope, request, state)) { "Deletion status is unavailable" }
        } else requireGeneration(state, request)
        DeletionRequestStatus(reference, request.revision, state.bindingRevision, request.status,
            request.acceptedAt.toString(), request.deadline.toString(), policy.adopted && request.status == "PENDING" &&
                now.isBefore(request.deadline) && state.deletionCauses.appOnly?.requestReference == reference)
    }!!

    private fun authorize(authority: TrustedPrivacyAuthority, purpose: PrivacyPurpose): Pair<AppProfilePolicy, Instant> {
        val policy = lockedPolicy(jdbc, mapper, authority.scope.application())
        val now = clock.instant() // Sample only after acquisition of the shared application lock.
        require(policy != null && (policy.adopted || purpose == PrivacyPurpose.READ_DELETION_STATUS)) {
            "Privacy capability is unavailable"
        }
        require(authority.purpose == purpose && authority.strong && !authority.authenticatedAt.isAfter(now) &&
            Duration.between(authority.authenticatedAt, now) <= maximumStrongAge && now.isBefore(authority.expiresAt)) {
            "Current recent Strong purpose-bound authority is required"
        }
        return policy to now
    }
    private fun current(scope: ProfileScope): BindingState {
        val state = bindings.binding(scope)
        check(state != null && state.lifecycle != Lifecycle.ERASED) { "Profile is unavailable" }
        return state
    }
    private fun requireGeneration(state: BindingState, request: StoredDeletionRequest) {
        check(state.profileReference == request.profileReference && state.generation == request.generation) { "Deletion request belongs to retired generation" }
    }
    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(values)).joinToString("") { "%02x".format(it) }
}
