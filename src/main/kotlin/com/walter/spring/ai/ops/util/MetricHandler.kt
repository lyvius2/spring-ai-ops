package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.connector.PrometheusConnector
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.controller.dto.PrometheusApplicationMetrics
import com.walter.spring.ai.ops.controller.dto.PrometheusNamedSeries
import com.walter.spring.ai.ops.controller.dto.PrometheusUptimeMetric
import com.walter.spring.ai.ops.util.extension.bytesToMb
import com.walter.spring.ai.ops.util.extension.percentOf
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class MetricHandler(
    private val prometheusConnector: PrometheusConnector,
) {
    companion object {
        private val APP_LABELS = listOf("application", "app", "service", "job")
        private const val STEP = "60s"
        private const val UPTIME_RANGE = 300L
    }

    fun getApplicationMetrics(appName: String, start: Long, end: Long, now: Instant): PrometheusApplicationMetrics {
        val memoryUsedBytes = queryRange("sum(${metricSelector("jvm_memory_used_bytes", appName)})", end - UPTIME_RANGE, end).lastValue()
        val memoryAllocatedBytes = queryRange("sum(${metricSelector("jvm_memory_committed_bytes", appName)} > 0)", end - UPTIME_RANGE, end).lastValue()
        val uptimeSeconds = queryRange(metricSelector("process_uptime_seconds", appName), end - UPTIME_RANGE, end).lastValue()

        return PrometheusApplicationMetrics(
            applicationName = appName,
            memoryUsedPercent = memoryUsedBytes.percentOf(memoryAllocatedBytes),
            memoryAllocatedMb = memoryAllocatedBytes?.bytesToMb(),
            memoryUsedMb = memoryUsedBytes?.bytesToMb(),
            uptime = buildUptime(uptimeSeconds, now),
            openFiles = queryRange(metricSelector("process_files_open_files", appName), start, end).aggregatePoints(),
            cpuUsage = buildCpuSeries(appName, start, end),
            averageLatency = queryRange(averageLatencyQuery(appName), start, end).aggregatePoints(),
            httpStatus = buildHttpStatusSeries(appName, start, end),
        )
    }

    private fun buildUptime(uptimeSeconds: Double?, now: Instant): PrometheusUptimeMetric =
        PrometheusUptimeMetric(
            startedAt = uptimeSeconds?.let { now.minusSeconds(it.toLong()).toString() },
            uptimeSeconds = uptimeSeconds,
        )

    private fun buildCpuSeries(appName: String, start: Long, end: Long): List<PrometheusNamedSeries> = listOf(
        namedSeries("System", metricSelector("system_cpu_usage", appName), start, end),
        namedSeries("Process", metricSelector("process_cpu_usage", appName), start, end),
    )

    private fun buildHttpStatusSeries(appName: String, start: Long, end: Long): List<PrometheusNamedSeries> = listOf(
        namedSeries("2xx", "sum(${rateSelector("http_server_requests_seconds_count", appName, "status=~\"2..\"")})", start, end),
        namedSeries("4xx", "sum(${rateSelector("http_server_requests_seconds_count", appName, "status=~\"4..\"")})", start, end),
        namedSeries("5xx", "sum(${rateSelector("http_server_requests_seconds_count", appName, "status=~\"5..\"")})", start, end),
    )

    private fun averageLatencyQuery(appName: String): String =
        "sum(${rateSelector("http_server_requests_seconds_sum", appName)}) / " +
        "sum(${rateSelector("http_server_requests_seconds_count", appName)})"

    private fun queryRange(query: String, start: Long, end: Long): PrometheusQueryResult {
        val result = prometheusConnector.queryRange(
            PrometheusQueryInquiry(
                query = query,
                start = start.toString(),
                end = end.toString(),
                step = STEP,
            )
        )
        if (result.errorMessage.isNotBlank() || result.error.isNotBlank()) {
            throw IllegalStateException(result.errorMessage.ifBlank { result.error })
        }
        return result
    }

    private fun namedSeries(name: String, query: String, start: Long, end: Long): PrometheusNamedSeries =
        PrometheusNamedSeries(name, queryRange(query, start, end).aggregatePoints())


    private fun metricSelector(metricName: String, appName: String, extraMatcher: String = ""): String =
        APP_LABELS.joinToString(" or ", prefix = "(", postfix = ")") { label ->
            "$metricName{${matchers(label, appName, extraMatcher)}}"
        }

    private fun rateSelector(metricName: String, appName: String, extraMatcher: String = ""): String =
        APP_LABELS.joinToString(" or ", prefix = "(", postfix = ")") { label ->
            "rate($metricName{${matchers(label, appName, extraMatcher)}}[5m])"
        }

    private fun matchers(label: String, appName: String, extraMatcher: String): String =
        listOf("""$label="${escapeLabelValue(appName)}"""", extraMatcher)
            .filter { it.isNotBlank() }
            .joinToString(",")

    private fun escapeLabelValue(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}