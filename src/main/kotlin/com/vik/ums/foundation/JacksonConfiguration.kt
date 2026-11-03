package com.vik.ums.foundation

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Boot 4 auto-configures Jackson 3 for HTTP, while the application's services parse and write
 * JSON with Jackson 2 explicitly. This bean keeps one mapper for them; its name avoids colliding
 * with Boot's `objectMapper`.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfiguration {
    @Bean
    fun umsObjectMapper(): ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
}
