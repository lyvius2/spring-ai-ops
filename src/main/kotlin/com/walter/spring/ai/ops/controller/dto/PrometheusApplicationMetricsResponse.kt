package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Prometheus application metrics dashboard response")
data class PrometheusApplicationMetricsResponse(
    @Schema(description = "Whether Prometheus is configured")
    val configured: Boolean,
    @Schema(description = "Metric rows, one per registered application")
    val applications: List<PrometheusApplicationMetrics> = emptyList(),
    @Schema(description = "Response generation timestamp in ISO-8601 format")
    val generatedAt: String = "",
    @Schema(description = "Error message when Prometheus metrics cannot be loaded")
    val errorMessage: String = "",
)

