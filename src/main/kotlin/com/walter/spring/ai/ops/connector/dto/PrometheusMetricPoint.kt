package com.walter.spring.ai.ops.connector.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A single time-series data point from Prometheus")
data class PrometheusMetricPoint(
    @Schema(description = "Unix epoch timestamp in seconds")
    val timestamp: Long,
    @Schema(description = "Metric value at the given timestamp")
    val value: Double,
)

