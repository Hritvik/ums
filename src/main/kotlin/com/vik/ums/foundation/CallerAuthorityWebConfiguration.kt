package com.vik.ums.foundation

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class CallerAuthorityWebConfiguration(
    private val interceptor: CallerAuthorityInterceptor,
    private val rateLimit: RateLimitInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**")
        registry.addInterceptor(rateLimit).addPathPatterns("/api/v1/**").order(1)
    }
}
