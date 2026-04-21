package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM_API_KEY
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.event.RateLimitHitEvent
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
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.RetryUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${ai.open-ai.model:gpt-4o-mini}") private val openAiModel: String,
    @Value("\${ai.open-ai.api-key:}") private val openAiApiKey: String,
    @Value("\${ai.anthropic.model:claude-sonnet-4-6}") private val anthropicModel: String,
    @Value("\${ai.anthropic.api-key:}") private val anthropicApiKey: String,
    @Value("\${ai.anthropic.max-tokens:8192}") private val anthropicMaxTokens: Int,
    @Value("\${ai.groq.model:llama-3.3-70b-versatile}") private val groqModel: String,
    @Value("\${ai.groq.api-key:}") private val groqApiKey: String,
    @Value("\${ai.groq.base-url:https://api.groq.com/openai}") private val groqBaseUrl: String,
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
            runCatching { chatModel = buildChatModel(LlmProvider.fromKey(llm), apiKey) }
                .onFailure { log.warn("Failed to restore ChatModel from Redis: {}", it.message) }
            return
        }

        val hasOpenAi = openAiApiKey.isNotBlank()
        val hasAnthropic = anthropicApiKey.isNotBlank()
        val hasGroq = groqApiKey.isNotBlank()
        val configuredCount = listOf(hasOpenAi, hasAnthropic, hasGroq).count { it }
        if (configuredCount == 1) {
            val (provider, key) = when {
                hasOpenAi    -> LlmProvider.OPEN_AI   to openAiApiKey
                hasAnthropic -> LlmProvider.ANTHROPIC to anthropicApiKey
                else         -> LlmProvider.GROQ      to groqApiKey
            }
            runCatching { configure(provider, key) }
                .onFailure { log.warn("Failed to auto-configure {} from yml: {}", provider.key, it.message) }
        }
    }

    fun isSelectProviderRequired(): Boolean {
        val configuredCount = listOf(openAiApiKey, anthropicApiKey, groqApiKey).count { it.isNotBlank() }
        return configuredCount > 1 && chatModel == null
    }

    fun configureFromYml(provider: LlmProvider) {
        val apiKey = when (provider) {
            LlmProvider.OPEN_AI   -> openAiApiKey
            LlmProvider.ANTHROPIC -> anthropicApiKey
            LlmProvider.GROQ      -> groqApiKey
        }
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key for '${provider.key}' is not configured in application.yml")
        }
        configure(provider, apiKey)
    }

    fun configure(provider: LlmProvider, apiKey: String) {
        val effectiveApiKey = apiKey.ifBlank {
            getCurrentApiKey() ?: throw IllegalStateException("API key is not configured. Please enter an API key.")
        }
        chatModel = buildChatModel(provider, effectiveApiKey)
        redisTemplate.opsForValue().set(REDIS_KEY_LLM, provider.key)
        redisTemplate.opsForValue().set(REDIS_KEY_LLM_API_KEY, cryptoProvider.encrypt(effectiveApiKey))
    }

    fun isConfigured(): Boolean {
        return chatModel != null
    }

    fun getCurrentLlm(): String? {
        return redisTemplate.opsForValue().get(REDIS_KEY_LLM)
    }

    private fun getCurrentApiKey(): String? {
        return redisTemplate.opsForValue().get(REDIS_KEY_LLM_API_KEY)
            ?.let { cryptoProvider.decrypt(it) }
    }

    fun getChatModel(): ChatModel {
        return chatModel ?: error("ChatModel is not configured")
    }

    private fun callWithRateLimitRetry(model: ChatModel, prompt: Prompt, maxRetries: Int = 3): String {
        repeat(maxRetries) { attempt ->
            try {
                val raw = model.call(prompt).result.output.text ?: ""
                return stripThinkingBlock(raw)
            } catch (e: NonTransientAiException) {
                val isRateLimit = e.message?.contains("rate_limit_error") == true || e.message?.contains("429") == true
                if (isRateLimit && attempt < maxRetries - 1) {
                    log.warn("Rate limit hit (attempt {}/{}), waiting 61s before retry...", attempt + 1, maxRetries)
                    eventPublisher.publishEvent(RateLimitHitEvent(this, attempt + 1, maxRetries))
                    Thread.sleep(61_000)
                } else {
                    throw e
                }
            }
        }
        error("Unreachable")
    }

    private fun buildChatModel(provider: LlmProvider, apiKey: String): ChatModel {
        val toolCallingManager = ToolCallingManager.builder().build()
        val retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE
        val observationRegistry = ObservationRegistry.NOOP

        return when (provider) {
            LlmProvider.OPEN_AI -> {
                val api = OpenAiApi.builder().apiKey(apiKey).build()
                val options = OpenAiChatOptions.builder().model(openAiModel).build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            LlmProvider.ANTHROPIC -> {
                val api = AnthropicApi.builder().apiKey(apiKey).build()
                val options = AnthropicChatOptions.builder().model(anthropicModel).maxTokens(anthropicMaxTokens).build()
                AnthropicChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            LlmProvider.GROQ -> {
                // Groq provides an OpenAI-compatible API endpoint
                val api = OpenAiApi.builder()
                    .baseUrl(groqBaseUrl)
                    .apiKey(apiKey)
                    .build()
                val options = OpenAiChatOptions.builder().model(groqModel).build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
        }
    }

    fun estimateTokenCount(bundle: String): Int {
        var asciiCount = 0
        var nonAsciiCount = 0
        for (ch in bundle) {
            if (ch.code < 128) asciiCount++ else nonAsciiCount++
        }
        return (asciiCount / 4) + nonAsciiCount
    }

    fun executeAnalyzeCodeRisk(bundle: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert static code analyzer specializing in security and reliability. " +
                    "Analyze the provided source code and identify concrete issues in the following categories: " +
                    "1. Security vulnerabilities, " +
                    "2. Bug risks, " +
                    "3. Null/exception handling issues, " +
                    "4. Concurrency issues, " +
                    "5. Resource leaks, " +
                    "6. Performance inefficiencies, " +
                    "7. Maintainability problems that can lead to defects. " +
                    "Reference file names where possible. Express uncertain findings as possibilities, not certainties. " +
                    "Do not state unverifiable facts as certainties; express them as possibilities."
        )
        val userMessage = UserMessage(
            buildString {
                append(bundle)
                appendLine()
                appendLine("Based on the above source code, provide a code risk analysis in the following format:")
                appendLine()
                appendLine("First, write a comprehensive markdown analysis report.")
                appendLine("Group findings by the 7 categories listed above.")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
                appendLine()
                appendLine("Then, after the markdown report, append the exact delimiter and a JSON array of all identified issues:")
                appendLine("---ISSUES_JSON_START---")
                appendLine("""[{"file":"relative/path/to/File.kt","line":"42","severity":"High","description":"**markdown** description of the issue","recommendation":"How to fix it, may include `code` snippets","codeSnippet":"exact source code lines related to the issue"}]""")
                appendLine("---ISSUES_JSON_END---")
                appendLine()
                appendLine("Rules for the JSON section:")
                appendLine("- 'file': use the relative path shown in '## File:' headers of the source bundle.")
                appendLine("- 'line': best-guess line number as a string, or null if unknown.")
                appendLine("- 'severity': exactly one of 'High', 'Medium', or 'Low'.")
                appendLine("- 'description' and 'recommendation': markdown-formatted strings (bold, inline code, lists are allowed).")
                appendLine("- 'codeSnippet': copy at most 3 of the most relevant source lines that directly show the issue. Plain text only — no markdown fences. Must be a valid JSON string: escape backslashes as \\\\, double-quotes as \\\", and newlines as \\n.")
                appendLine("- If no issues are found, use an empty array [].")
                appendLine("- Output valid JSON only between the delimiters — no trailing commas, no comments.")
                appendLine("- CRITICAL: every string value must be valid JSON. Special characters in codeSnippet (backslash, quote, dollar sign, newline) must be properly escaped.")
            }
        )
        llmRateLimiter.acquire()
        return try {
            callWithRateLimitRetry(model, Prompt(listOf(systemMessage, userMessage)))
        } finally {
            llmRateLimiter.release()
        }
    }

    fun executeFinalAnalyzeCode(issues: List<String>): String {
        val model = chatModel ?: return ""
        if (issues.isEmpty()) return ""
        val systemMessage = SystemMessage(
            "You are an expert code analyst. " +
                    "You will receive multiple partial code risk analysis reports from different parts of the same codebase. " +
                    "Your task is to deduplicate overlapping issues, consolidate related findings, " +
                    "and produce a single comprehensive final report in markdown format."
        )
        val combined = issues.mapIndexed { index, report ->
            "## Analysis Part ${index + 1}\n$report"
        }.joinToString("\n\n---\n\n")
        val userMessage = UserMessage(
            buildString {
                appendLine(combined)
                appendLine()
                appendLine("Synthesize the above partial analyses into a final comprehensive code risk report with:")
                appendLine("1. Executive summary")
                appendLine("2. High severity issues (deduplicated and consolidated)")
                appendLine("3. Medium severity issues (deduplicated and consolidated)")
                appendLine("4. Low severity issues (deduplicated and consolidated)")
                appendLine("5. Overall recommendations")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
            }
        )
        llmRateLimiter.acquire()
        return try {
            callWithRateLimitRetry(model, Prompt(listOf(systemMessage, userMessage)))
        } finally {
            llmRateLimiter.release()
        }
    }

    fun executeAnalyzeFiring(alertSection: String, logSection: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert in analyzing application errors and logs. " +
                    "Analyze the provided Grafana alert context and application logs, " +
                    "identify the root cause, and give clear, actionable recommendations. " +
                    "Do not state unverifiable facts as certainties; express them as possibilities."
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
            callWithRateLimitRetry(model, Prompt(listOf(systemMessage, userMessage)))
        } finally {
            llmRateLimiter.release()
        }
    }

    fun executeAnalyzeCodeDiffer(codeReviewSection: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert code reviewer. " +
                    "Analyze the provided code diff and give a thorough code review. " +
                    "Focus on correctness, potential bugs, performance, security, and code quality. " +
                    "Do not state unverifiable facts as certainties; express them as possibilities."
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
            callWithRateLimitRetry(model, Prompt(listOf(systemMessage, userMessage)))
        } finally {
            llmRateLimiter.release()
        }
    }

    private fun stripThinkingBlock(text: String): String =
        text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
}
