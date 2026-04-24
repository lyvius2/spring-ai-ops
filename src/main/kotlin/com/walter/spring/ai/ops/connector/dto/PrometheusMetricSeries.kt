package com.walter.spring.ai.ops.connector.dto

/**
 * A single metric time-series returned by Prometheus range query.
 * - metric: label set identifying the series (e.g. __name__, job, instance, ...)
 * - values: list of [unixTimestamp, sampleValue] pairs (both as String)
 */
data class PrometheusMetricSeries(
    val metric: Map<String, String> = emptyMap(),
    val values: List<List<String>> = emptyList(),
)

