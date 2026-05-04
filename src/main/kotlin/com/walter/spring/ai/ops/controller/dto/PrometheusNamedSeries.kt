package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.connector.dto.PrometheusMetricPoint
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A named metric series containing a list of time-series data points")
data class PrometheusNamedSeries(
    @Schema(description = "Series label (e.g. instance or status code)")
    val name: String,
    @Schema(description = "Time-series data points for this series")
    val points: List<PrometheusMetricPoint> = emptyList(),
)

