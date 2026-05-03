package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "LLM configuration status per provider")
data class LlmStatusResponse(
    @Schema(description = "Currently active LLM provider key", nullable = true)
    val usageLlm: String?,
    @Schema(description = "Whether LLM is ready to use")
    val configured: Boolean,
    @Schema(description = "List of provider keys that have a stored API key")
    val savedProviders: List<String>,
)

