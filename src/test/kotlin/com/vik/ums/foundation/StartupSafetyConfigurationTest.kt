package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.Base64
import java.util.UUID

class StartupSafetyConfigurationTest {
    private fun valid(): MockEnvironment = MockEnvironment()
        .withProperty("ums.security.issuer", "https://iam.example.test")
        .withProperty("ums.security.jwk-set-uri", "https://iam.example.test/jwks")
        .withProperty("ums.security.audience", "https://ums.example.test")
        .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/ums")
        .withProperty("spring.datasource.username", "ums")
        .withProperty("spring.datasource.password", "secret")
        .withProperty("ums.environment", "test")
        .withProperty("ums.profile.locale-version", "l1")
        .withProperty("ums.profile.timezone-version", "t1")
        .withProperty("ums.profile.locales", "en")
        .withProperty("ums.profile.timezones", "UTC")
        .withProperty("ums.profile.receipt-key", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        .withProperty("ums.profile.privacy.max-strong-age-seconds", "300")
        .withProperty("ums.security.registrations",
            """[{"appId":"dutch-development","clientId":"dutch-mobile","audience":"https://ums.example.test",
                "allowedScopes":["profile:read"],"requiresDpop":true}]""")
        .withProperty("ums.iam.issuer-environment-ref", UUID.randomUUID().toString())

    @Test fun `a complete configuration is accepted`() {
        assertThatCode { StartupSafetyConfiguration(valid()) }.doesNotThrowAnyException()
    }

    @Test fun `a runtime test authority is refused`() {
        assertThatThrownBy { StartupSafetyConfiguration(valid().withProperty("ums.security.test-authority", "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `non-HTTPS or ambiguous issuer URLs are refused`() {
        listOf(
            "http://iam.example.test",
            "https://",
            "https://user:secret@iam.example.test",
            "https://iam.example.test#fragment",
            "not a url",
        ).forEach { value ->
            assertThatThrownBy { StartupSafetyConfiguration(valid().withProperty("ums.security.issuer", value)) }
                .describedAs(value).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `a non-PostgreSQL datasource is refused`() {
        assertThatThrownBy {
            StartupSafetyConfiguration(valid().withProperty("spring.datasource.url", "jdbc:h2:mem:test"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `a malformed or unpublished environment name is refused`() {
        listOf("TEST ENV", "local", "production-eu").forEach { value ->
            assertThatThrownBy { StartupSafetyConfiguration(valid().withProperty("ums.environment", value)) }
                .describedAs(value).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `empty locale and timezone catalogues are refused`() {
        assertThatThrownBy {
            StartupSafetyConfiguration(valid().withProperty("ums.profile.locales", " , "))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            StartupSafetyConfiguration(valid().withProperty("ums.profile.timezones", ""))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `a weak or malformed receipt key is refused`() {
        listOf("not-base64!", Base64.getEncoder().encodeToString(ByteArray(8) { 1 })).forEach { value ->
            assertThatThrownBy {
                StartupSafetyConfiguration(valid().withProperty("ums.profile.receipt-key", value))
            }.describedAs(value).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `the strong-authentication window is bounded`() {
        listOf("0", "86401", "not-a-number").forEach { value ->
            assertThatThrownBy {
                StartupSafetyConfiguration(valid().withProperty("ums.profile.privacy.max-strong-age-seconds", value))
            }.describedAs(value).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `malformed registrations and issuer references are refused`() {
        assertThatThrownBy {
            StartupSafetyConfiguration(valid().withProperty("ums.security.registrations", "[]"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            StartupSafetyConfiguration(valid().withProperty("ums.iam.issuer-environment-ref", "not-a-uuid"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `every required property absence is refused`() {
        listOf(
            "ums.security.issuer", "ums.security.jwk-set-uri", "ums.security.audience",
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "ums.environment", "ums.profile.locale-version", "ums.profile.timezone-version",
            "ums.profile.locales", "ums.profile.timezones", "ums.profile.receipt-key",
            "ums.profile.privacy.max-strong-age-seconds", "ums.security.registrations",
            "ums.iam.issuer-environment-ref",
        ).forEach { property ->
            val environment = valid()
            environment.setProperty(property, "")
            assertThatThrownBy { StartupSafetyConfiguration(environment) }
                .describedAs(property).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
