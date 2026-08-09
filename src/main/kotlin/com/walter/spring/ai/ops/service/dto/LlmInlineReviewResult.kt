package com.walter.spring.ai.ops.service.dto

data class LlmInlineReviewResult(
    val summary: String = "",
    val comments: List<LlmInlineComment> = emptyList(),
)