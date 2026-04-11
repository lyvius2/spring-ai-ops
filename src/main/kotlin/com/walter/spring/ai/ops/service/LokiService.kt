package com.walter.spring.ai.ops.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class LokiService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${loki.url:}") private val lokiUrlFromConfig: String,
) {
    fun isConfigured(): Boolean {
        return try {
            lokiUrlFromConfig.isNotBlank() || redisTemplate.opsForValue().get("lokiUrl")?.isNotBlank() == true
        } catch (_: Exception) {
            false
        }
    }

    fun setLokiUrl(lokiUrl: String) {
        redisTemplate.opsForValue().set("lokiUrl", lokiUrl)
    }
}