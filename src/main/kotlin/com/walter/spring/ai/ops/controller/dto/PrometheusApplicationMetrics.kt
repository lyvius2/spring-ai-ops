package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.connector.dto.PrometheusMetricPoint
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Prometheus metric summary for one application")
data class PrometheusApplicationMetrics(
    val applicationName: String,
    val memoryUsedPercent: Double? = null,
    val memoryAllocatedMb: Double? = null,
    val memoryUsedMb: Double? = null,
    val uptime: PrometheusUptimeMetric? = null,
    val openFiles: List<PrometheusMetricPoint> = emptyList(),
    val cpuUsage: List<PrometheusNamedSeries> = emptyList(),
    val averageLatency: List<PrometheusMetricPoint> = emptyList(),
    val httpStatus: List<PrometheusNamedSeries> = emptyList(),
) {
    val hasData: Boolean
        get() = memoryUsedPercent != null ||
            memoryAllocatedMb != null ||
            memoryUsedMb != null ||
            uptime?.uptimeSeconds != null ||
            openFiles.isNotEmpty() ||
            cpuUsage.any { it.points.isNotEmpty() } ||
            averageLatency.isNotEmpty() ||
            httpStatus.any { it.points.isNotEmpty() }
}

