package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.PrometheusApplicationMetricsResponse
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigRequest
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigResponse
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigured
import com.walter.spring.ai.ops.service.PrometheusService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Prometheus Configuration", description = "Prometheus base URL configuration for metric queries")
@RestController
@RequestMapping("/api/prometheus")
class PrometheusConfigController(
    private val prometheusService: PrometheusService,
) {
    @Operation(summary = "Get Prometheus configuration status")
    @GetMapping("/status")
    fun prometheusStatus(): PrometheusConfigured {
        val prometheusUrl = prometheusService.getPrometheusUrl()
        return PrometheusConfigured(prometheusUrl.isNotBlank(), prometheusUrl)
    }

    @Operation(summary = "Get Prometheus application metrics for registered applications")
    @GetMapping("/application-metrics")
    fun prometheusApplicationMetrics(@RequestParam("apps", required = false) apps: List<String>?): PrometheusApplicationMetricsResponse {
        return prometheusService.getApplicationMetrics(apps ?: emptyList())
    }

    @Operation(
        summary = "Save Prometheus base URL",
        description = "Validates the connection to the given URL before persisting it in Redis."
    )
    @PostMapping("/config")
    fun configurePrometheus(@RequestBody request: PrometheusConfigRequest): PrometheusConfigResponse {
        return runCatching {
            prometheusService.setPrometheusUrl(request.url)
            PrometheusConfigResponse.success()
        }.getOrElse { PrometheusConfigResponse.failure(it as Exception) }
    }
}