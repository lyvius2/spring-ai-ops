package com.walter.spring.ai.ops.config.base

import feign.Client
import feign.Request
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Abstract base configuration for external service connectors (GitHub, GitLab, Loki, etc.).
 *
 * Provides a Feign [feign.Client] bean that resolves the target URL dynamically per request,
 * combining a placeholder URL in the [@FeignClient] annotation with a runtime value
 * sourced from Redis or application properties.
 *
 * Default resolution strategy: **Redis takes priority** over the property-configured URL.
 * Subclasses may override [resolveUrl] to change the resolution order
 * (e.g. [com.walter.spring.ai.ops.config.LokiConnectorConfig] uses property-first).
 *
 * To add a new external connector:
 * 1. Extend this class and provide [placeholderUrl], [configuredUrl], [redisUrlKey].
 * 2. Declare a `const val PLACEHOLDER_URL` in a companion object.
 * 3. Reference it in `@FeignClient(url = PLACEHOLDER_URL)`.
 */
abstract class DynamicConnectorConfig {

    protected abstract val redisTemplate: StringRedisTemplate

    /** Default URL from application.yml */
    protected abstract val configuredUrl: String

    /** Redis key under which the user-supplied URL is persisted */
    protected abstract val redisUrlKey: String

    /** Placeholder URL declared in the @FeignClient annotation */
    abstract val placeholderUrl: String

    /**
     * Resolves the effective URL for this connector.
     * Default: Redis value takes priority over [configuredUrl].
     */
    protected open fun resolveUrl(): String =
        redisTemplate.opsForValue().get(redisUrlKey)?.takeIf { it.isNotBlank() }
            ?: configuredUrl

    @Bean
    fun externalClient(): Client = Client { request, options ->
        val resolvedUrl = resolveUrl()
        val resolvedRequest = Request.create(
            request.httpMethod(),
            request.url().replace(placeholderUrl, resolvedUrl),
            request.headers(),
            request.body(),
            request.charset(),
            request.requestTemplate(),
        )
        Client.Default(null, null).execute(resolvedRequest, options)
    }
}