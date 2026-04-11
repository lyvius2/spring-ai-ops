package com.walter.spring.ai.ops.config

import feign.Client
import feign.Request
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

class LokiConnectorConfig(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${loki.url:}") private val lokiUrlFromConfig: String,
) {
    companion object {
        const val PLACEHOLDER_URL = "http://loki-placeholder"
    }

    @Bean
    fun lokiClient(): Client = Client { request, options ->
        val lokiUrl = lokiUrlFromConfig.ifBlank {
            redisTemplate.opsForValue().get("lokiUrl") ?: ""
        }
        val resolvedRequest = Request.create(
            request.httpMethod(),
            request.url().replace(PLACEHOLDER_URL, lokiUrl),
            request.headers(),
            request.body(),
            request.charset(),
            request.requestTemplate(),
        )
        Client.Default(null, null).execute(resolvedRequest, options)
    }
}