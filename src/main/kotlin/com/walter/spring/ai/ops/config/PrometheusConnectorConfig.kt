package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_PROMETHEUS_URL
import com.walter.spring.ai.ops.config.base.DynamicConnectorConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate

class PrometheusConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    @Value("\${prometheus.url:}") override val configuredUrl: String,
    @Value("\${feign.prometheus.connect-timeout:5000}") override val connectTimeout: Long,
    @Value("\${feign.prometheus.read-timeout:30000}") override val readTimeout: Long,
) : DynamicConnectorConfig() {

    companion object {
        const val PLACEHOLDER_URL = "http://127.0.0.1:9090"
    }

    override val placeholderUrl: String = PLACEHOLDER_URL
    override val redisUrlKey: String = REDIS_KEY_PROMETHEUS_URL
}

