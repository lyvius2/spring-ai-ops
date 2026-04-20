package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Loki configuration status")
data class LokiConfigured(
    @Schema(description = "Whether a Loki URL is configured")
    @JsonProperty("isConfigured")
    val isConfigured: Boolean,
    @Schema(description = "Configured Loki base URL", example = "http://loki:3100")
    val lokiUrl: String = "",
)