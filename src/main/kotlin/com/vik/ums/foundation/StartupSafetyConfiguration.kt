package com.vik.ums.foundation

import com.vik.ums.iam.CallerRegistrations
import java.net.URI
import java.util.UUID
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** Reject incomplete deployment configuration without including any supplied values in errors. */
@Configuration(proxyBeanMethods = false)
class StartupSafetyConfiguration(environment: Environment) {
    init {
        require(!environment.containsProperty("ums.security.test-authority")) {
            "ums.security.test-authority must not be configured in the runtime"
        }
        requireHttps(environment, "ums.security.issuer")
        requireHttps(environment, "ums.security.jwk-set-uri")
        required(environment, "ums.security.audience")
        require(required(environment, "spring.datasource.url").startsWith("jdbc:postgresql://")) {
            "spring.datasource.url must identify a PostgreSQL database"
        }
        required(environment, "spring.datasource.username")
        required(environment, "spring.datasource.password")
        require(required(environment, "ums.environment") in setOf("development", "test", "staging", "production")) {
            "ums.environment must be development, test, staging, or production"
        }
        required(environment, "ums.profile.locale-version")
        required(environment, "ums.profile.timezone-version")
        require(split(environment, "ums.profile.locales").isNotEmpty()) { "ums.profile.locales must not be empty" }
        require(split(environment, "ums.profile.timezones").isNotEmpty()) { "ums.profile.timezones must not be empty" }
        require(runCatching { java.util.Base64.getDecoder()
            .decode(required(environment, "ums.profile.receipt-key")) }.getOrNull()?.size?.let { it >= 32 } == true) {
            "ums.profile.receipt-key must be a base64 key of at least 32 bytes"
        }
        require(required(environment, "ums.profile.privacy.max-strong-age-seconds").toLongOrNull()?.let { it in 1..86400 } == true) {
            "ums.profile.privacy.max-strong-age-seconds must be between 1 and 86400"
        }
        // No default caller registrations and no guessed app binding.
        CallerRegistrations.parse(required(environment, "ums.security.registrations"))
        runCatching { UUID.fromString(required(environment, "ums.iam.issuer-environment-ref")) }
            .getOrElse { throw IllegalArgumentException("ums.iam.issuer-environment-ref must be a UUID") }
        if (environment.getProperty("ums.iam.ingress.audience")?.isNotBlank() == true) {
            requireHttps(environment, "ums.iam.ingress.audience")
        }
        if (environment.getProperty("ums.iam.ingress.enabled", "false").toBoolean()) {
            required(environment, "ums.iam.app-registrations")
            com.vik.ums.iam.WorkloadRegistrations.parse(required(environment, "ums.iam.workload-registrations"))
        }
    }

    private fun required(environment: Environment, property: String): String {
        val value = environment.getProperty(property)
        require(!value.isNullOrBlank()) { "$property must be explicitly configured" }
        return value
    }

    private fun split(environment: Environment, property: String): Set<String> =
        required(environment, property).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun requireHttps(environment: Environment, property: String) {
        val uri = runCatching { URI(required(environment, property)) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null) {
            "$property must be a valid HTTPS URL without embedded credentials or a fragment"
        }
    }
}
