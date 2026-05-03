package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_PROMETHEUS_URL
import com.walter.spring.ai.ops.connector.PrometheusConnector
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.controller.dto.PrometheusApplicationMetricsResponse
import com.walter.spring.ai.ops.util.MetricHandler
import com.walter.spring.ai.ops.util.extension.verifyHttpConnection
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Instant

@Service
class PrometheusService(
    private val redisTemplate: StringRedisTemplate,
    private val prometheusConnector: PrometheusConnector,
    private val metricHandler: MetricHandler,
    @Value("\${prometheus.url:}") private val prometheusUrlFromConfig: String,
) {
    companion object {
        private const val RANGE_SECONDS = 3600L
    }

    fun isConfigured(): Boolean = getPrometheusUrl().isNotBlank()

    fun getPrometheusUrl(): String =
        redisTemplate.opsForValue().get(REDIS_KEY_PROMETHEUS_URL)?.takeIf { it.isNotBlank() }
            ?: prometheusUrlFromConfig

    fun setPrometheusUrl(prometheusUrl: String) {
        // Prometheus URL is optional — allow blank to clear but validate if a value is provided
        if (prometheusUrl.isNotBlank()) {
            URI(prometheusUrl).verifyHttpConnection()
        }
        redisTemplate.opsForValue().set(REDIS_KEY_PROMETHEUS_URL, prometheusUrl)
    }

    fun executeMetricQuery(inquiry: PrometheusQueryInquiry): PrometheusQueryResult {
        return prometheusConnector.queryRange(inquiry)
    }

    fun getApplicationMetrics(applications: List<String>): PrometheusApplicationMetricsResponse {
        if (!isConfigured()) return PrometheusApplicationMetricsResponse(configured = false)

        val now = Instant.now()
        val end = now.epochSecond
        val start = end - RANGE_SECONDS

        return runCatching {
            val rows = applications.sorted()
                .map { appName -> metricHandler.getApplicationMetrics(appName, start, end, now) }
                .filter { it.hasData }

            PrometheusApplicationMetricsResponse(
                configured = true,
                applications = rows,
                generatedAt = now.toString(),
            )
        }.getOrElse { e ->
            PrometheusApplicationMetricsResponse(
                configured = true,
                generatedAt = now.toString(),
                errorMessage = e.message ?: "Failed to load Prometheus metrics.",
            )
        }
    }
}
