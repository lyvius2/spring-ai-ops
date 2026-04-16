package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LOKI_URL
import com.walter.spring.ai.ops.config.base.DynamicConnectorConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate

class LokiConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    @Value("\${loki.url:}") override val configuredUrl: String,
) : DynamicConnectorConfig() {

    companion object {
        const val PLACEHOLDER_URL = "http://127.0.0.1:3100"
    }

    override val placeholderUrl: String = PLACEHOLDER_URL
    override val redisUrlKey: String = REDIS_KEY_LOKI_URL
}
