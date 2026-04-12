package com.walter.spring.ai.ops.config

import feign.RequestInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

class GithubConnectorConfig(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${github.access-token:}") private val configuredToken: String,
) {
    @Bean
    fun githubAuthInterceptor(): RequestInterceptor = RequestInterceptor { template ->
        val token = redisTemplate.opsForValue().get("githubToken")
            ?.takeIf { it.isNotBlank() }
            ?: configuredToken.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            template.header("Authorization", "Bearer $token")
            template.header("Accept", "application/vnd.github+json")
            template.header("X-GitHub-Api-Version", "2022-11-28")
        }
    }
}
