package com.walter.spring.ai.ops.service

import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class AiClientService(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(AiClientService::class.java)

    @Volatile
    private var chatModel: ChatModel? = null

    @PostConstruct
    fun initialize() {
        val llm = redisTemplate.opsForValue().get("llm") ?: return
        val apiKey = redisTemplate.opsForValue().get("llmKey") ?: return
        runCatching { chatModel = buildChatModel(llm, apiKey) }
            .onFailure { log.warn("Failed to restore ChatModel from Redis: {}", it.message) }
    }

    fun configure(llm: String, apiKey: String) {
        chatModel = buildChatModel(llm, apiKey)
        redisTemplate.opsForValue().set("llm", llm)
        redisTemplate.opsForValue().set("llmKey", apiKey)
    }

    fun isConfigured(): Boolean = chatModel != null

    fun getCurrentLlm(): String? = redisTemplate.opsForValue().get("llm")

    fun getChatModel(): ChatModel = chatModel ?: error("ChatModel is not configured")

    private fun buildChatModel(llm: String, apiKey: String): ChatModel {
        val toolCallingManager = ToolCallingManager.builder().build()
        val retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE
        val observationRegistry = ObservationRegistry.NOOP

        return when (llm) {
            "openai" -> {
                val api = OpenAiApi.builder().apiKey(apiKey).build()
                val options = OpenAiChatOptions.builder().model("gpt-4o-mini").build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            "anthropic" -> {
                val api = AnthropicApi.builder().apiKey(apiKey).build()
                val options = AnthropicChatOptions.builder().model("claude-3-5-sonnet-20241022").build()
                AnthropicChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            else -> throw IllegalArgumentException("Unknown LLM provider: $llm")
        }
    }
}
