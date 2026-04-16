package com.walter.spring.ai.ops.controller.dto

data class AiConfigRequest(
    val llm: String,
    val apiKey: String,
) {
    override fun toString(): String = "AiConfigRequest(llm=$llm, apiKey=****)"
}
