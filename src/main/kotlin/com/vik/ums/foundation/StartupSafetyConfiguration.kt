package com.vik.ums.foundation

import java.net.URI
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
    }

    private fun required(environment: Environment, property: String): String {
        val value = environment.getProperty(property)
        require(!value.isNullOrBlank()) { "$property must be explicitly configured" }
        return value
    }

    private fun requireHttps(environment: Environment, property: String) {
        val uri = runCatching { URI(required(environment, property)) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null) {
            "$property must be a valid HTTPS URL without embedded credentials or a fragment"
        }
    }
}
