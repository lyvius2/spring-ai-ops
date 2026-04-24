package com.walter.spring.ai.ops.connector.dto

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Top-level response DTO for Prometheus /api/v1/query_range.
 *
 * Example response shape:
 * {
 *   "status": "success",
 *   "data": {
 *     "resultType": "matrix",
 *     "result": [
 *       {
 *         "metric": { "__name__": "http_requests_total", "job": "api-server" },
 *         "values": [ [1609459200, "42"], [1609459215, "43"] ]
 *       }
 *     ]
 *   }
 * }
 */
data class PrometheusQueryResult(
    val status: String = "",
    val data: PrometheusData? = null,
    val errorType: String = "",
    val error: String = "",
    val errorMessage: String = "",
) {
    fun isSuccess(): Boolean = status == "success" && data != null

    /**
     * Builds a prompt section summarising the collected metric data for LLM analysis.
     */
    fun createMetricSectionPrompt(): String {
        if (!isSuccess()) {
            return buildString {
                appendLine("## Application Metrics")
                appendLine("(Metric data unavailable${if (errorMessage.isNotBlank()) ": $errorMessage" else "."})")
            }
        }
        return buildString {
            appendLine("## Application Metrics")
            val series = data?.result ?: emptyList()
            if (series.isEmpty()) {
                appendLine("(No metric data available for the given time range.)")
                return@buildString
            }
            series.forEach { s ->
                val metricName = s.metric["__name__"] ?: "(unnamed)"
                val labels = s.metric.filterKeys { it != "__name__" }
                    .entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }
                appendLine("### Metric: $metricName{$labels}")
                s.values.forEach { point ->
                    val timestamp = point.getOrNull(0)
                        ?.toDoubleOrNull()
                        ?.toLong()
                        ?.let { epochSeconds ->
                            Instant.ofEpochSecond(epochSeconds)
                                .atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        } ?: ""
                    val value = point.getOrNull(1) ?: ""
                    appendLine("[$timestamp] $value")
                }
            }
        }
    }
}

