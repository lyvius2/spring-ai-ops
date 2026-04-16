package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM_API_KEY
import com.walter.spring.ai.ops.util.CryptoProvider
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.Semaphore

@Service
class AiModelService(
    private val redisTemplate: StringRedisTemplate,
    private val cryptoProvider: CryptoProvider,
    @Qualifier("llmRateLimiter") private val llmRateLimiter: Semaphore,
    @Value("\${ai.open-ai.model:gpt-4o-mini}") private val openAiModel: String,
    @Value("\${ai.open-ai.api-key:}") private val openAiApiKey: String,
    @Value("\${ai.anthropic.model:claude-sonnet-4-6}") private val anthropicModel: String,
    @Value("\${ai.anthropic.api-key:}") private val anthropicApiKey: String,
    @Value("\${analysis.result-language:en}") private val resultLanguage: String,
) {
    private val log = LoggerFactory.getLogger(AiModelService::class.java)
    private val languageOptions = "The analysis results should be derived in the '${resultLanguage}' language."

    @Volatile
    private var chatModel: ChatModel? = null

    @EventListener(ApplicationStartedEvent::class)
    fun initialize() {
        val llm = redisTemplate.opsForValue().get(REDIS_KEY_LLM)
        val apiKey = redisTemplate.opsForValue().get(REDIS_KEY_LLM_API_KEY)
            ?.let { cryptoProvider.decrypt(it) }
        if (!llm.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            runCatching { chatModel = buildChatModel(llm, apiKey) }
                .onFailure { log.warn("Failed to restore ChatModel from Redis: {}", it.message) }
            return
        }

        val hasOpenAi = openAiApiKey.isNotBlank()
        val hasAnthropic = anthropicApiKey.isNotBlank()
        if (hasOpenAi && !hasAnthropic) {
            runCatching { configure("openai", openAiApiKey) }
                .onFailure { log.warn("Failed to auto-configure OpenAI from yml: {}", it.message) }
        } else if (hasAnthropic && !hasOpenAi) {
            runCatching { configure("anthropic", anthropicApiKey) }
                .onFailure { log.warn("Failed to auto-configure Anthropic from yml: {}", it.message) }
        }
    }

    fun isSelectProviderRequired(): Boolean {
        return openAiApiKey.isNotBlank() && anthropicApiKey.isNotBlank() && chatModel == null
    }

    fun configureFromYml(llm: String) {
        val apiKey = when (llm) {
            "openai" -> openAiApiKey
            "anthropic" -> anthropicApiKey
            else -> throw IllegalArgumentException("Unknown LLM provider: $llm")
        }
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key for '$llm' is not configured in application.yml")
        }
        configure(llm, apiKey)
    }

    fun configure(llm: String, apiKey: String) {
        chatModel = buildChatModel(llm, apiKey)
        redisTemplate.opsForValue().set(REDIS_KEY_LLM, llm)
        redisTemplate.opsForValue().set(REDIS_KEY_LLM_API_KEY, cryptoProvider.encrypt(apiKey))
    }

    fun isConfigured(): Boolean {
        return chatModel != null
    }

    fun getCurrentLlm(): String? {
        return redisTemplate.opsForValue().get(REDIS_KEY_LLM)
    }

    fun getChatModel(): ChatModel {
        return chatModel ?: error("ChatModel is not configured")
    }

    private fun buildChatModel(llm: String, apiKey: String): ChatModel {
        val toolCallingManager = ToolCallingManager.builder().build()
        val retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE
        val observationRegistry = ObservationRegistry.NOOP

        return when (llm) {
            "openai" -> {
                val api = OpenAiApi.builder().apiKey(apiKey).build()
                val options = OpenAiChatOptions.builder().model(openAiModel).build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            "anthropic" -> {
                val api = AnthropicApi.builder().apiKey(apiKey).build()
                val options = AnthropicChatOptions.builder().model(anthropicModel).maxTokens(1024).build()
                AnthropicChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            else -> throw IllegalArgumentException("Unknown LLM provider: $llm")
        }
    }

    fun executeAnalyzeFiring(alertSection: String, logSection: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert in analyzing application errors and logs. " +
                    "Analyze the provided Grafana alert context and application logs, " +
                    "identify the root cause, and give clear, actionable recommendations."
        )
        val userMessage = UserMessage(
            buildString {
                append(alertSection)
                appendLine()
                append(logSection)
                appendLine()
                appendLine("Based on the above alert and logs, please provide in markdown format:")
                appendLine("1. Root cause analysis")
                appendLine("2. Affected components")
                appendLine("3. Recommended actions to resolve the issue")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
            }
        )
        llmRateLimiter.acquire()
        return try {
            val response = model.call(Prompt(listOf(systemMessage, userMessage)))
            response.result.output.text ?: ""
        } finally {
            llmRateLimiter.release()
        }
    }

    fun executeAnalyzeCodeDiffer(codeReviewSection: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert code reviewer. " +
                    "Analyze the provided code diff and give a thorough code review. " +
                    "Focus on correctness, potential bugs, performance, security, and code quality."
        )
        val userMessage = UserMessage(
            buildString {
                append(codeReviewSection)
                appendLine()
                appendLine("Based on the above diff, please provide in markdown format:")
                appendLine("1. Summary of changes")
                appendLine("2. Potential issues or bugs")
                appendLine("3. Security considerations")
                appendLine("4. Suggestions for improvement")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
            }
        )
        llmRateLimiter.acquire()
        return try {
            val response = model.call(Prompt(listOf(systemMessage, userMessage)))
            response.result.output.text ?: ""
        } finally {
            llmRateLimiter.release()
        }
    }
}
