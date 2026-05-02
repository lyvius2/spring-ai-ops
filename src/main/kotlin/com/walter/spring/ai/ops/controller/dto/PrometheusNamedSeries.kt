package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.connector.dto.PrometheusMetricPoint

data class PrometheusNamedSeries(
    val name: String,
    val points: List<PrometheusMetricPoint> = emptyList(),
)

