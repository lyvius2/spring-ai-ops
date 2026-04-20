package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for saving the Loki base URL")
data class LokiConfigRequest(
    @Schema(description = "Loki base URL", example = "http://loki:3100", required = true)
    val url: String
)