package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class AcceptedAudiencesValidatorTest {
    private val human = "https://ums.example.test"
    private val workload = "https://ums.example.test/internal/iam/events"
    private val validator = AcceptedAudiencesValidator(setOf(human, workload))

    private fun token(vararg audiences: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "ES256").claims { it["sub"] = "x" }
        .issuedAt(Instant.parse("2026-11-08T07:00:00Z")).expiresAt(Instant.parse("2026-11-08T07:05:00Z"))
        .audience(audiences.toList()).build()

    @Test fun `either registered audience is accepted alone`() {
        assertThat(validator.validate(token(human)).hasErrors()).isFalse()
        assertThat(validator.validate(token(workload)).hasErrors()).isFalse()
    }

    @Test fun `missing, foreign and multiple audiences are rejected`() {
        assertThat(validator.validate(token("https://other.example.test")).hasErrors()).isTrue()
        assertThat(validator.validate(token(human, workload)).hasErrors()).isTrue()
        val none = Jwt.withTokenValue("token").header("alg", "ES256").claims { it["sub"] = "x" }
            .issuedAt(Instant.parse("2026-11-08T07:00:00Z")).expiresAt(Instant.parse("2026-11-08T07:05:00Z")).build()
        assertThat(validator.validate(none).hasErrors()).isTrue()
    }
}
