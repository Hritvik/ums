package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StartupSafetyTest {
    private val requiredProperties = mapOf(
        "ums.security.issuer" to "https://iam.example.test",
        "ums.security.jwk-set-uri" to "https://iam.example.test/.well-known/jwks.json",
        "ums.security.audience" to "https://ums.example.test",
        "ums.environment" to "test",
        "ums.iam.issuer-environment-ref" to "6f1d4a2c-3b5e-4c7d-8a9b-0c1d2e3f4a5b",
        "ums.security.registrations" to
            """[{"appId":"dutch-development","clientId":"dutch-mobile","audience":"https://ums.example.test","allowedScopes":["profile:read"],"requiresDpop":true}]""",
        "spring.datasource.url" to "jdbc:postgresql://localhost:5432/ums",
        "spring.datasource.username" to "ums",
        "spring.datasource.password" to "test-only-password",
    )

    private fun context(overrides: Map<String, String> = emptyMap()): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(StartupSafetyConfiguration::class.java)
            .withPropertyValues(*(requiredProperties + overrides).map { "${it.key}=${it.value}" }.toTypedArray())

    @Test
    fun `explicit production configuration starts without contacting IAM or database`() {
        context().run { assertThat(it).hasNotFailed() }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "ums.security.issuer", "ums.security.jwk-set-uri", "ums.security.audience",
        "ums.security.registrations", "ums.iam.issuer-environment-ref", "ums.environment",
        "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
    ])
    fun `blank mandatory setting fails startup without exposing credentials`(property: String) {
        context(mapOf(property to "")).run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining(property)
            assertThat(it.startupFailure).hasStackTraceContaining("must")
            assertThat(it.startupFailure?.stackTraceToString()).doesNotContain("test-only-password")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ums.security.issuer", "ums.security.jwk-set-uri"])
    fun `insecure IAM endpoint fails startup`(property: String) {
        context(mapOf(property to "http://iam.example.test")).run { assertThat(it).hasFailed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://user:secret@iam.example.test", "https://iam.example.test/#fragment", "not a URI", "https:///missing-host"])
    fun `malformed or credential-bearing IAM endpoint fails startup`(endpoint: String) {
        context(mapOf("ums.security.issuer" to endpoint)).run { assertThat(it).hasFailed() }
    }

    @Test
    fun `non PostgreSQL database fails startup`() {
        context(mapOf("spring.datasource.url" to "jdbc:h2:mem:ums")).run { assertThat(it).hasFailed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["true", "false", "local"])
    fun `runtime test authority setting is rejected regardless of profile`(value: String) {
        context(mapOf("ums.security.test-authority" to value, "spring.profiles.active" to "test"))
            .run { assertThat(it).hasFailed() }
    }
}
