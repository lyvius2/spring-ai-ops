package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.LokiConfigRequest
import com.walter.spring.ai.ops.controller.dto.LokiConfigResponse
import com.walter.spring.ai.ops.controller.dto.LokiConfigured
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigRequest
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigResponse
import com.walter.spring.ai.ops.controller.dto.PrometheusConfigured
import com.walter.spring.ai.ops.controller.dto.PrometheusApplicationMetricsResponse
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.PrometheusService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Observability Configuration", description = "Loki and Prometheus base URL configuration for log and metric queries")
@RestController
@RequestMapping("/api")
class LokiConfigController(
    private val lokiService: LokiService,
    private val prometheusService: PrometheusService,
) {
    @Operation(summary = "Get Loki configuration status")
    @GetMapping("/loki/status")
    fun lokiStatus(): LokiConfigured {
        val lokiUrl = lokiService.getLokiUrl()
        return LokiConfigured(lokiUrl.isNotBlank(), lokiUrl)
    }

    @Operation(
        summary = "Save Loki base URL",
        description = "Validates the connection to the given URL before persisting it in Redis."
    )
    @PostMapping("/loki/config")
    fun configureLoki(@RequestBody request: LokiConfigRequest): LokiConfigResponse {
        return runCatching {
            lokiService.setLokiUrl(request.url)
            LokiConfigResponse.success()
        }.getOrElse { LokiConfigResponse.failure(it as Exception) }
    }

    @Operation(summary = "Get Prometheus configuration status")
    @GetMapping("/prometheus/status")
    fun prometheusStatus(): PrometheusConfigured {
        val prometheusUrl = prometheusService.getPrometheusUrl()
        return PrometheusConfigured(prometheusUrl.isNotBlank(), prometheusUrl)
    }

    @Operation(summary = "Get Prometheus application metrics for registered applications")
    @GetMapping("/prometheus/application-metrics")
    fun prometheusApplicationMetrics(@RequestParam("apps", required = false) apps: List<String>?): PrometheusApplicationMetricsResponse {
        return prometheusService.getApplicationMetrics(apps ?: emptyList())
    }

    @Operation(
        summary = "Save Prometheus base URL",
        description = "Validates the connection to the given URL before persisting it in Redis."
    )
    @PostMapping("/prometheus/config")
    fun configurePrometheus(@RequestBody request: PrometheusConfigRequest): PrometheusConfigResponse {
        return runCatching {
            prometheusService.setPrometheusUrl(request.url)
            PrometheusConfigResponse.success()
        }.getOrElse { PrometheusConfigResponse.failure(it as Exception) }
    }
}
