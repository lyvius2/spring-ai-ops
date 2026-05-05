package com.walter.spring.ai.ops.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val csrfTokenInterceptor: CsrfTokenInterceptor,
    private val authenticationInterceptor: AuthenticationInterceptor,
    private val passwordChangeRequiredInterceptor: PasswordChangeRequiredInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(passwordChangeRequiredInterceptor)
            .addPathPatterns("/api/**")
        registry.addInterceptor(authenticationInterceptor)
            .addPathPatterns("/api/**")
        registry.addInterceptor(csrfTokenInterceptor)
            .addPathPatterns("/api/code-risk/**")
    }
}

