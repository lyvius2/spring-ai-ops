package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_FIRING_PREFIX
import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.util.zSetPushWithTtl
import com.walter.spring.ai.ops.util.zSetRangeAllDesc
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GrafanaService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${analysis.data-retention-hours:120}") private val retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") private val maximumViewCount: Long,
) {
    fun convertLogInquiry(request: GrafanaAlertingRequest): LokiQueryInquiry {
        val alert = request.alerts.firstOrNull { it.isFiring() }
            ?: request.alerts.firstOrNull()
            ?: throw IllegalArgumentException("No alerts found in the Grafana request.")

        val lokiLabels = alert.lokiLabels()
        if (lokiLabels.isEmpty()) {
            throw IllegalArgumentException(
                "No Loki-compatible labels found in alert '${alert.alertName()}'. " +
                "Ensure Prometheus and Loki share the same label set (job, instance, etc.)."
            )
        }

        val streamSelector = lokiLabels.entries
            .joinToString(separator = ", ", prefix = "{", postfix = "}") { (k, v) ->
                val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
                """$k="$escaped""""
            }

        return LokiQueryInquiry(
            query = streamSelector,
            start = alert.lokiStartNano(),
            end = alert.lokiEndNano()
        )
    }

    fun saveAnalyzeFiringRecord(record: AnalyzeFiringRecord) {
        val key = "${REDIS_KEY_FIRING_PREFIX}${record.application}"
        redisTemplate.zSetPushWithTtl(key, objectMapper.writeValueAsString(record), retentionHours)
    }

    fun getAnalyzeFiringRecords(application: String): List<AnalyzeFiringRecord> {
        val key = "${REDIS_KEY_FIRING_PREFIX}${application}"
        return redisTemplate.zSetRangeAllDesc(key, maximumViewCount)
            .mapNotNull { runCatching { objectMapper.readValue(it, AnalyzeFiringRecord::class.java) }.getOrNull() }
    }
}