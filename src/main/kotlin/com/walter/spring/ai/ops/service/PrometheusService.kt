package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_PROMETHEUS_URL
import com.walter.spring.ai.ops.connector.PrometheusConnector
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.util.verifyHttpConnection
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.net.URI

@Service
class PrometheusService(
    private val redisTemplate: StringRedisTemplate,
    private val prometheusConnector: PrometheusConnector,
    @Value("\${prometheus.url:}") private val prometheusUrlFromConfig: String,
) {
    fun isConfigured(): Boolean = getPrometheusUrl().isNotBlank()

    fun getPrometheusUrl(): String =
        redisTemplate.opsForValue().get(REDIS_KEY_PROMETHEUS_URL)?.takeIf { it.isNotBlank() }
            ?: prometheusUrlFromConfig

    fun setPrometheusUrl(prometheusUrl: String) {
        // Prometheus URL is optional — allow blank to clear, but validate if a value is provided
        if (prometheusUrl.isNotBlank()) {
            URI(prometheusUrl).verifyHttpConnection()
        }
        redisTemplate.opsForValue().set(REDIS_KEY_PROMETHEUS_URL, prometheusUrl)
    }

    fun executeMetricQuery(inquiry: PrometheusQueryInquiry): PrometheusQueryResult {
        return prometheusConnector.queryRange(inquiry)
    }
}

