package com.walter.spring.ai.ops.code

enum class LlmProvider(
    val product: String,
) {
    OPEN_AI("ChatGPT"),
    ANTHROPIC("Claude")
}