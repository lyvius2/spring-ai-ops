package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for triggering a code risk analysis")
data class CodeRiskRequest(
    @Schema(description = "Target application name", example = "my-service", required = true)
    val appName: String,
    @Schema(description = "Git branch to analyse", example = "main", required = true)
    val branch: String,
)
