package com.walter.spring.ai.ops.controller.dto

data class PrometheusUptimeMetric(
    val startedAt: String? = null,
    val uptimeSeconds: Double? = null,
)

