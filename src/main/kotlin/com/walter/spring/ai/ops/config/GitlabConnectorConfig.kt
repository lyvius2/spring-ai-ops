package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_URL
import com.walter.spring.ai.ops.config.base.DynamicConnectorConfig
import feign.RequestInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

class GitlabConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    @Value("\${gitlab.url:https://gitlab.com/api/v4}") override val configuredUrl: String,
    @Value("\${gitlab.access-token:}") private val configuredToken: String,
    @Value("\${feign.gitlab.connect-timeout:5000}") override val connectTimeout: Long,
    @Value("\${feign.gitlab.read-timeout:30000}") override val readTimeout: Long,
) : DynamicConnectorConfig() {

    companion object {
        const val PLACEHOLDER_URL = "https://gitlab.com/api/v4"
    }

    override val placeholderUrl: String = PLACEHOLDER_URL
    override val redisUrlKey: String = REDIS_KEY_GITLAB_URL

    @Bean
    fun gitlabAuthInterceptor(): RequestInterceptor = RequestInterceptor { template ->
        val token = redisTemplate.opsForValue().get(REDIS_KEY_GITLAB_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: configuredToken.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            template.header("PRIVATE-TOKEN", token)
        }
    }
}

