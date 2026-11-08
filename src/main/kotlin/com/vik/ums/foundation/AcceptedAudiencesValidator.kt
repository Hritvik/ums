package com.vik.ums.foundation

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * The decoder accepts either the human resource audience or the workload ingress audience, but
 * exactly one value. Each route's resolver then enforces its own exact audience and token profile,
 * so a token accepted here still cannot cross from one surface to the other.
 */
class AcceptedAudiencesValidator(private val audiences: Set<String>) : OAuth2TokenValidator<Jwt> {
    init { require(audiences.isNotEmpty() && audiences.none { it.isBlank() }) }

    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val value = token.audience?.singleOrNull()
        return if (value != null && value in audiences) OAuth2TokenValidatorResult.success()
        else OAuth2TokenValidatorResult.failure(
            OAuth2Error("invalid_token", "Required audience is missing", null),
        )
    }
}
