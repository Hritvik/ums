package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import com.vik.ums.iam.CallerRegistrations
import com.vik.ums.iam.IamCallerResolver
import com.vik.ums.profiles.ProfileScopeResolver
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
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

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
    fun callerRegistrations(environment: Environment): CallerRegistrations =
        CallerRegistrations.parse(environment.getProperty("ums.security.registrations"))

    @Bean
    fun callerResolver(environment: Environment, registrations: CallerRegistrations): IamCallerResolver =
        IamCallerResolver(environment.getRequiredProperty("ums.security.issuer"), registrations)

    @Bean
    fun callerAuthorityInterceptor(resolver: IamCallerResolver, mapper: ObjectMapper): CallerAuthorityInterceptor =
        CallerAuthorityInterceptor(resolver, mapper)

    @Bean
    fun profileScopeResolver(jdbc: JdbcTemplate, environment: Environment): ProfileScopeResolver =
        ProfileScopeResolver(jdbc, UUID.fromString(environment.getRequiredProperty("ums.iam.issuer-environment-ref")))

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        // There is no browser session or cookie authentication in this resource server.
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .requestCache { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/profile/status").authenticated()
                .anyRequest().denyAll()
        }
        .oauth2ResourceServer { it.jwt {} }
        .build()
}
