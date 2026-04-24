package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for saving the Prometheus base URL")
data class PrometheusConfigRequest(
    @Schema(description = "Prometheus base URL", example = "http://prometheus:9090", required = true)
    val url: String
)

