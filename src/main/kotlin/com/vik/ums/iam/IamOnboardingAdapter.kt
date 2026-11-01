package com.vik.ums.iam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileProvisioningService
import com.vik.ums.profiles.ProvisioningOutcome
import com.vik.ums.profiles.ProvisioningResult
import com.vik.ums.profiles.TrustedOnboardingFact
import com.vik.ums.profiles.lockedPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.UUID

/** Shared producer and stream identity, so adapter and repair can never drift apart. */
internal object IamOnboardingIdentity {
    fun producer(issuerEnvironmentRef: UUID): String = "iam:$issuerEnvironmentRef"
    fun stream(subjectReference: UUID): String = "iam-onboarding:$subjectReference"
}

/** Raised when the payload names an application this deployment does not serve. */
class UnregisteredIamApplicationException(message: String) : IllegalArgumentException(message)

/** Raised when the payload's application disagrees with the authenticated producer binding. */
class IamApplicationMismatchException(message: String) : IllegalArgumentException(message)

/** Unwired inbound seam. The transport must establish source trust before calling this adapter. */
class IamOnboardingAdapter(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val provisioning: ProfileProvisioningService,
    private val source: TrustedIamSource,
    registrations: Map<String, AppScope>,
) {
    private val registrations = Collections.unmodifiableMap(registrations.toMap())
    private val parser = IamOnboardingParser(source)
    private val persistence = IamOnboardingPersistence(jdbc, source)
    private val mapper = jacksonObjectMapper()
    init {
        require(registrations.keys.all { it.matches(Regex("[a-z][a-z0-9-]{2,63}")) })
        require(registrations.values.all { it.issuerEnvironmentRef == source.issuerEnvironmentRef })
        require(registrations.values.toSet().size == registrations.size)
    }

    fun consume(json: String, authenticatedApplicationId: String): ProvisioningResult {
        val event = parser.parse(json)
        // The transport-attributed application must match the payload; neither side decides alone.
        if (event.appRegistrationId != authenticatedApplicationId) {
            throw IamApplicationMismatchException("Payload application does not match the authenticated producer binding")
        }
        val app = registrations[event.appRegistrationId]
            ?: throw UnregisteredIamApplicationException("IAM application is not registered")
        return transactions.execute {
            val policy = lockedPolicy(jdbc, mapper, app)
            if (policy?.adopted != true) return@execute ProvisioningResult(ProvisioningOutcome.NOT_ADOPTED)
            if (event.reason == "RECONCILIATION") {
                persistence.knownReceipt(event)
                return@execute ProvisioningResult(ProvisioningOutcome.RECONCILIATION_REQUIRED)
            }
            persistence.bindSourceAndApp(app, event.appRegistrationId)
            val eventReference = persistence.receipt(event, app)
            val (scope, pinnedActivation) = persistence.subject(app, event)
            if (pinnedActivation != event.activationId)
                return@execute ProvisioningResult(ProvisioningOutcome.RECONCILIATION_REQUIRED)
            provisioning.provision(TrustedOnboardingFact(scope,
                IamOnboardingIdentity.producer(source.issuerEnvironmentRef), eventReference,
                IamOnboardingIdentity.stream(scope.subjectReference), event.onboardingVersion, event.correlationId))
        }!!
    }
}
