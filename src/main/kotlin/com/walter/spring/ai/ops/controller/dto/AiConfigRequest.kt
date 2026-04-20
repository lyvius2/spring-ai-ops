package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for saving LLM provider and API key")
data class AiConfigRequest(
    @Schema(description = "LLM provider key (openai / anthropic / groq)", example = "openai", required = true)
    val llm: String,
    @Schema(description = "API key for the selected LLM provider", example = "sk-...", required = true)
    val apiKey: String,
)
