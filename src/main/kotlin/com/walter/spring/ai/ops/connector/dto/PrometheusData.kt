package com.walter.spring.ai.ops.connector.dto

/**
 * The `data` envelope of a Prometheus query_range response.
 * - resultType: always "matrix" for range queries
 * - result: list of metric time-series
 */
data class PrometheusData(
    val resultType: String = "",
    val result: List<PrometheusMetricSeries> = emptyList(),
)

