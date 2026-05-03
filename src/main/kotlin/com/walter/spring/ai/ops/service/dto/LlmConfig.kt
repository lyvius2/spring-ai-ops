package com.walter.spring.ai.ops.service.dto

import com.walter.spring.ai.ops.code.LlmProvider

data class LlmConfig(
    val provider: LlmProvider,
    var apiKey: String? = null,
) {
}