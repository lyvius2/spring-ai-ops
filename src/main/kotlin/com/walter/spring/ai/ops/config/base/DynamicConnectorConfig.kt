package com.walter.spring.ai.ops.config.base

import feign.Client
import feign.Request
import feign.okhttp.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.TimeUnit

abstract class DynamicConnectorConfig {
    protected abstract val redisTemplate: StringRedisTemplate
    protected abstract val configuredUrl: String
    protected abstract val redisUrlKey: String
    protected abstract val connectTimeout: Long
    protected abstract val readTimeout: Long

    abstract val placeholderUrl: String

    protected open fun resolveUrl(): String =
        redisTemplate.opsForValue().get(redisUrlKey)?.takeIf { it.isNotBlank() }
            ?: configuredUrl

    protected val httpClient: OkHttpClient = OkHttpClient()

    @Bean
    fun feignOptions(): Request.Options =
        Request.Options(connectTimeout, TimeUnit.MILLISECONDS, readTimeout, TimeUnit.MILLISECONDS, true)

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
        httpClient.execute(resolvedRequest, options)
    }
}