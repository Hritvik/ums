package com.vik.ums.foundation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt

class AudienceValidatorTest {
    private fun validator(): OAuth2TokenValidator<Jwt> = AudienceValidator("ums")

    @Test
    fun `expected audience permits validation`() {
        assertThat(validator().validate(token(listOf("other", "ums"))).hasErrors()).isFalse()
    }

    @Test
    fun `different audience is rejected`() {
        assertThat(validator().validate(token(listOf("other"))).hasErrors()).isTrue()
    }

    @Test
    fun `missing audience is rejected`() {
        assertThat(validator().validate(token(null)).hasErrors()).isTrue()
    }

    private fun token(audiences: List<String>?): Jwt = Jwt.withTokenValue("test-only-token")
        .header("alg", "RS256").subject("app-subject")
        .apply { if (audiences != null) audience(audiences) }
        .build()
}
