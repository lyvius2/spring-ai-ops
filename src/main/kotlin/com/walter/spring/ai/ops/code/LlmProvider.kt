package com.walter.spring.ai.ops.code

enum class LlmProvider(
    val product: String,
    val key: String,
) {
    OPEN_AI("ChatGPT", "openai"),
    ANTHROPIC("Claude", "anthropic"),
    DEEP_SEEK("DeepSeek", "deepseek");

    companion object {
        fun fromKey(key: String): LlmProvider =
            entries.find { it.key == key }
                ?: throw IllegalArgumentException("Unknown LLM provider key: $key")
    }
}