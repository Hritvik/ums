package com.vik.ums.iam

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.vik.ums.profiles.AppScope
import com.vik.ums.profiles.ProfileProvisioningService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The onboarding ingress is off unless a deployment enables it with an explicit audience, app
 * registration set and reviewed workload bindings. Nothing here has a production default.
 *
 * Deployment inputs:
 *
 * | Property | Meaning |
 * |---|---|
 * | `ums.iam.ingress.enabled` | `true` exposes `POST /internal/v1/iam/events`; default off |
 * | `ums.iam.ingress.audience` | the exact workload audience IAM registers for the consumer |
 * | `ums.iam.ingress.required-scope` | default `ums:onboarding:write` |
 * | `ums.iam.ingress.max-body-bytes` | default 32768, bounded to 1 MiB |
 * | `ums.iam.workload-registrations` | reviewed JSON array of workload bindings |
 * | `ums.iam.app-registrations` | reviewed JSON map of app registration to app reference |
 *
 * The token issuer and JWKS come from `ums.security.issuer` and `ums.security.jwk-set-uri`.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ums.iam.ingress.enabled"], havingValue = "true")
class IamIngressConfiguration {
    @Bean
    fun trustedIamSource(environment: Environment): TrustedIamSource = TrustedIamSource(
        UUID.fromString(environment.getRequiredProperty("ums.iam.issuer-environment-ref")),
        environment.getRequiredProperty("ums.environment"),
    )

    @Bean
    fun iamOnboardingAdapter(jdbc: JdbcTemplate, transactions: TransactionTemplate,
                             provisioning: ProfileProvisioningService, source: TrustedIamSource,
                             environment: Environment): IamOnboardingAdapter =
        IamOnboardingAdapter(jdbc, transactions, provisioning, source,
            appRegistrations(environment.getRequiredProperty("ums.iam.app-registrations"), source))

    @Bean
    fun iamEventIngestionService(jdbc: JdbcTemplate, adapter: IamOnboardingAdapter,
                                 environment: Environment, clock: java.time.Clock): IamEventIngestionService =
        IamEventIngestionService(jdbc, adapter, "iam",
            environment.getProperty("ums.iam.ingress.max-body-bytes", (32 * 1024).toString()).toInt(), clock)

    @Bean
    fun workloadRegistrations(environment: Environment): WorkloadRegistrations =
        WorkloadRegistrations.parse(environment.getRequiredProperty("ums.iam.workload-registrations"))

    @Bean
    fun workloadCallerResolver(environment: Environment, registrations: WorkloadRegistrations,
                               clock: java.time.Clock): WorkloadCallerResolver =
        WorkloadCallerResolver(
            issuer = environment.getRequiredProperty("ums.security.issuer"),
            audience = environment.getRequiredProperty("ums.iam.ingress.audience"),
            requiredScope = environment.getProperty("ums.iam.ingress.required-scope", "ums:onboarding:write"),
            registrations = registrations,
            clock = clock,
        )


    /** Strict map of app registration id to the app reference UMS already serves. */
    internal fun appRegistrations(configured: String, source: TrustedIamSource): Map<String, AppScope> {
        val parsed = runCatching { MAPPER.readValue<Map<String, String>>(configured) }
            .getOrElse { throw IllegalArgumentException("App registrations are malformed") }
        require(parsed.isNotEmpty()) { "App registrations must not be empty" }
        return parsed.mapValues { (registration, reference) ->
            require(registration.matches(Regex("[a-z][a-z0-9-]{2,63}"))) { "App registration is malformed" }
            AppScope(source.issuerEnvironmentRef, UUID.fromString(reference))
        }
    }

    private companion object {
        val MAPPER = ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .registerModule(KotlinModule.Builder().build())
    }
}
