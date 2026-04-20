package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for selecting the active LLM provider")
data class SelectProviderRequest(
    @Schema(description = "LLM provider key (openai / anthropic / groq)", example = "groq", required = true)
    val llm: String,
)
