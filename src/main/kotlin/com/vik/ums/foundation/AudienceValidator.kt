package com.vik.ums.foundation

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

class AudienceValidator(private val requiredAudience: String) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (token.audience?.contains(requiredAudience) == true) OAuth2TokenValidatorResult.success()
        else OAuth2TokenValidatorResult.failure(
            OAuth2Error("invalid_token", "Required audience is missing", null),
        )
}
