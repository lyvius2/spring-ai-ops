package com.walter.spring.ai.ops.controller.dto

data class AiConfigRequest(
    val llm: String,
    val apiKey: String,
)
