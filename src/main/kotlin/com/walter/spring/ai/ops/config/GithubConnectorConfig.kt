package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_TOKEN
import com.walter.spring.ai.ops.config.base.DynamicConnectorConfig
import feign.RequestInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

class GithubConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    @Value("\${github.url:https://api.github.com}") override val configuredUrl: String,
    @Value("\${github.access-token:}") private val configuredToken: String,
    @Value("\${github.api-version:}") private val apiVersion: String,
    @Value("\${feign.github.connect-timeout:5000}") override val connectTimeout: Long,
    @Value("\${feign.github.read-timeout:30000}") override val readTimeout: Long,
) : DynamicConnectorConfig() {

    companion object {
        const val PLACEHOLDER_URL = "https://api.github.com"
    }

    override val placeholderUrl: String = PLACEHOLDER_URL
    override val redisUrlKey: String = REDIS_KEY_GITHUB_URL

    @Bean
    fun githubAuthInterceptor(): RequestInterceptor = RequestInterceptor { template ->
        val token = redisTemplate.opsForValue().get(REDIS_KEY_GITHUB_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: configuredToken.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            template.header("Authorization", "Bearer $token")
            template.header("Accept", "application/vnd.github+json")
            template.header("X-GitHub-Api-Version", apiVersion)
        }
    }
}
