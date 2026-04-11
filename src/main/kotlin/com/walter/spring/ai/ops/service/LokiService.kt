package com.walter.spring.ai.ops.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.net.HttpURLConnection
import java.net.URI

@Service
class LokiService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${loki.url:}") private val lokiUrlFromConfig: String,
) {
    fun isConfigured(): Boolean {
        return getLokiUrl().isNotBlank()
    }

    fun getLokiUrl(): String {
        return lokiUrlFromConfig.ifBlank {
            redisTemplate.opsForValue().get("lokiUrl") ?: ""
        }
    }

    fun setLokiUrl(lokiUrl: String) {
        verifyConnection(lokiUrl)
        redisTemplate.opsForValue().set("lokiUrl", lokiUrl)
    }

    private fun verifyConnection(lokiUrl: String) {
        try {
            val connection = URI(lokiUrl).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            connection.connect()
            connection.disconnect()
        } catch (e: Exception) {
            throw RuntimeException("Cannot connect to Loki at '$lokiUrl': ${e.message}")
        }
    }
}