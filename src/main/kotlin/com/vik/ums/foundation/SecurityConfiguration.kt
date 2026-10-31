package com.vik.ums.foundation

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
@DependsOn("startupSafetyConfiguration")
class SecurityConfiguration {
    @Bean
    fun jwtDecoder(environment: Environment): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(environment.getRequiredProperty("ums.security.jwk-set-uri")).build()
        val audience = environment.getRequiredProperty("ums.security.audience")
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(environment.getRequiredProperty("ums.security.issuer")),
            AudienceValidator(audience),
        ))
        return decoder
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        // There is no browser session or cookie authentication in this resource server.
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .requestCache { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .anyRequest().denyAll()
        }
        .oauth2ResourceServer { it.jwt {} }
        .build()
}
