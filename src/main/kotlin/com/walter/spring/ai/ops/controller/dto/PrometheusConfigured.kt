package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Prometheus configuration status")
data class PrometheusConfigured(
    @Schema(description = "Whether a Prometheus URL is configured")
    @JsonProperty("isConfigured")
    val isConfigured: Boolean,
    @Schema(description = "Configured Prometheus base URL", example = "http://prometheus:9090")
    val prometheusUrl: String = "",
)

