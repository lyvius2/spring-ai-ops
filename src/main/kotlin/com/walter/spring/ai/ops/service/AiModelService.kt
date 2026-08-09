package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM_APIS
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_USAGE_LLM
import com.walter.spring.ai.ops.controller.dto.LlmStatusResponse
import com.walter.spring.ai.ops.event.RateLimitHitEvent
import com.walter.spring.ai.ops.service.dto.LlmConfig
import com.walter.spring.ai.ops.util.CryptoProvider
import com.walter.spring.ai.ops.util.extension.getArrayList
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
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
    private val objectMapper: ObjectMapper,
    @Qualifier("llmRateLimiter") private val llmRateLimiter: Semaphore,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${ai.open-ai.model:gpt-4o-mini}") private val openAiModel: String,
    @Value("\${ai.open-ai.api-key:}") private val openAiApiKey: String,
    @Value("\${ai.anthropic.model:claude-sonnet-4-6}") private val anthropicModel: String,
    @Value("\${ai.anthropic.api-key:}") private val anthropicApiKey: String,
    @Value("\${ai.anthropic.max-tokens:8192}") private val anthropicMaxTokens: Int,
    @Value("\${ai.deepseek.model:deepseek-v4-pro}") private val deepseekModel: String,
    @Value("\${ai.deepseek.api-key:}") private val deepseekApiKey: String,
    @Value("\${ai.deepseek.base-url:https://api.deepseek.com}") private val deepseekBaseUrl: String,
    @Value("\${ai.exaone.model:LGAI-EXAONE/K-EXAONE-236B-A23B}") private val exaoneModel: String,
    @Value("\${ai.exaone.api-key:}") private val exaoneApiKey: String,
    @Value("\${ai.exaone.base-url:https://api.friendli.ai/serverless}") private val exaoneBaseUrl: String,
    @Value("\${ai.bedrock.region:us-east-1}") private val bedrockRegion: String,
    @Value("\${ai.bedrock.model:us.amazon.nova-pro-v1:0}") private val bedrockModel: String,
    @Value("\${ai.bedrock.access-key:}") private val bedrockAccessKey: String,
    @Value("\${ai.bedrock.secret-key:}") private val bedrockSecretKey: String,
    @Value("\${analysis.result-language:en}") private val resultLanguage: String,
) {
    private val log = LoggerFactory.getLogger(AiModelService::class.java)
    private val languageOptions = "The analysis results should be derived in the '${resultLanguage}' language."

    @Volatile private var chatModel: ChatModel? = null
    @Volatile private var bedrockChatModel: BedrockProxyChatModel? = null

    @EventListener(ApplicationStartedEvent::class)
    fun initialize() {
        val llmConfigs = redisTemplate.getArrayList(REDIS_KEY_LLM_APIS, LlmConfig::class.java)
        var llmConfigsUpdated = false
        for (provider in LlmProvider.entries) {
            val matchedLlmConfig: LlmConfig? = llmConfigs.firstOrNull { it.provider == provider }
            if (matchedLlmConfig == null) {
                llmConfigs.add(createLlmDefaultConfig(provider))
                llmConfigsUpdated = true
                continue
            }
            if (matchedLlmConfig.apiKey.isNullOrBlank()) {
                val defaultApiKey = createLlmDefaultConfig(provider).apiKey
                if (!defaultApiKey.isNullOrBlank()) {
                    matchedLlmConfig.apiKey = defaultApiKey
                    llmConfigsUpdated = true
                }
            }
        }

        if (llmConfigsUpdated) {
            redisTemplate.opsForValue().set(REDIS_KEY_LLM_APIS, objectMapper.writeValueAsString(llmConfigs))
        }

        val ymlKeyCount = listOf(openAiApiKey, anthropicApiKey, deepseekApiKey).count { it.isNotBlank() }
        val fallbackUsage = if (ymlKeyCount < 2) {
            llmConfigs.firstOrNull { !it.apiKey.isNullOrBlank() }?.provider?.key
        } else {
            null
        }
        val savedUsageLlm = redisTemplate.opsForValue().get(REDIS_KEY_USAGE_LLM)?.takeIf { it.isNotBlank() }
        val usageLlm: String = savedUsageLlm ?: fallbackUsage ?: ""
        if (usageLlm.isBlank()) {
            return
        }
        if (savedUsageLlm == null) {
            redisTemplate.opsForValue().set(REDIS_KEY_USAGE_LLM, usageLlm)
        }

        val matchedLlmConfig: LlmConfig? = llmConfigs.firstOrNull { it.provider.key == usageLlm }
        if (listOf(bedrockRegion, bedrockModel).all { it.isNotBlank() }) {
            runCatching { bedrockChatModel = buildBedrockChatModel() }
                .onFailure { log.warn("Failed to initialize Bedrock LLM config from application settings.") }
        }
        if (matchedLlmConfig != null) {
            val apiKey = cryptoProvider.decrypt(matchedLlmConfig.apiKey)
            if (apiKey.isNotBlank()) {
                runCatching { chatModel = buildChatModel(matchedLlmConfig.provider, apiKey) }
                    .onFailure { log.warn("Failed to restore LLM config from Redis: {}", it.message) }
            }
            return
        }
    }

    private fun createLlmDefaultConfig(provider: LlmProvider): LlmConfig {
        val rawKey = when (provider) {
            LlmProvider.OPEN_AI -> openAiApiKey
            LlmProvider.ANTHROPIC -> anthropicApiKey
            LlmProvider.DEEP_SEEK -> deepseekApiKey
            LlmProvider.EXAONE -> exaoneApiKey
            LlmProvider.BEDROCK -> ""
        }
        val encryptedKey = rawKey.takeIf { it.isNotBlank() }?.let { cryptoProvider.encrypt(it) }
        return LlmConfig(provider, encryptedKey)
    }

    fun isSelectProviderRequired(): Boolean {
        val configuredCount = listOf(openAiApiKey, anthropicApiKey, deepseekApiKey, exaoneApiKey).count { it.isNotBlank() }
        val bedrockConfigured = if (listOf(bedrockRegion, bedrockModel).all { it.isNotBlank() }) 1 else 0
        return (configuredCount + bedrockConfigured) >= 2 && chatModel == null
    }

    fun configureFromYml(provider: LlmProvider) {
        val apiKey = when (provider) {
            LlmProvider.OPEN_AI -> openAiApiKey
            LlmProvider.ANTHROPIC -> anthropicApiKey
            LlmProvider.DEEP_SEEK -> deepseekApiKey
            LlmProvider.EXAONE -> exaoneApiKey
            LlmProvider.BEDROCK -> ""
        }
        if (LlmProvider.BEDROCK != provider && apiKey.isBlank()) {
            throw IllegalStateException("API key for '${provider.key}' is not configured in application.yml")
        }
        configure(provider, apiKey)
    }

    fun configure(provider: LlmProvider, apiKey: String) {
        if (provider == LlmProvider.BEDROCK) {
            if (bedrockRegion.isBlank()) {
                throw IllegalStateException("Bedrock region is not configured in application.yml or environment variables")
            }
            redisTemplate.opsForValue().set(REDIS_KEY_USAGE_LLM, provider.key)
            chatModel = bedrockChatModel ?: throw IllegalStateException("Amazon Bedrock LLM is not configured")
            return
        }
        val llmConfigs = redisTemplate.getArrayList(REDIS_KEY_LLM_APIS, LlmConfig::class.java)
        val existingConfig = llmConfigs.firstOrNull { it.provider == provider }
        val effectiveApiKey = apiKey.ifBlank {
            existingConfig?.apiKey
                ?.let { cryptoProvider.decrypt(it) }
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("API key is not configured. Please enter an API key.")
        }

        val encryptedKey = cryptoProvider.encrypt(effectiveApiKey)
        if (existingConfig != null) {
            existingConfig.apiKey = encryptedKey
        } else {
            llmConfigs.add(LlmConfig(provider, encryptedKey))
        }
        redisTemplate.opsForValue().set(REDIS_KEY_LLM_APIS, objectMapper.writeValueAsString(llmConfigs))
        redisTemplate.opsForValue().set(REDIS_KEY_USAGE_LLM, provider.key)

        chatModel = buildChatModel(provider, effectiveApiKey)
    }

    fun isConfigured(): Boolean {
        return chatModel != null
    }

    fun getCurrentStatus(): LlmStatusResponse {
        val savedProviders = LlmProvider.entries
            .filter { hasApiKey(it) }
            .map { it.key }
        return LlmStatusResponse(getCurrentLlm(), isConfigured(), savedProviders)
    }

    fun getCurrentLlm(): String? {
        return redisTemplate.opsForValue().get(REDIS_KEY_USAGE_LLM)
    }

    fun hasApiKey(provider: LlmProvider): Boolean {
        if (provider == LlmProvider.BEDROCK) {
            return bedrockRegion.isNotBlank()
        }
        val llmConfigs = redisTemplate.getArrayList(REDIS_KEY_LLM_APIS, LlmConfig::class.java)
        return llmConfigs.any { it.provider == provider && !it.apiKey.isNullOrBlank() }
    }

    private fun callWithRateLimitRetry(model: ChatModel, prompt: Prompt, maxRetries: Int = 3): String {
        repeat(maxRetries) { attempt ->
            try {
                return model.call(prompt).result.output.text ?: ""
            } catch (e: NonTransientAiException) {
                val msg = e.message ?: ""
                when {
                    msg.contains("402") || msg.contains("Insufficient Balance") || msg.contains("insufficient_balance") ->
                        throw IllegalStateException("LLM API call failed: insufficient balance. Please top up your account on the provider's platform.", e)
                    msg.contains("401") || msg.contains("invalid_api_key") || msg.contains("Unauthorized") ->
                        throw IllegalStateException("LLM API call failed: invalid or expired API key. Please reconfigure your API key.", e)
                    msg.contains("rate_limit_error") || msg.contains("429") -> {
                        if (attempt < maxRetries - 1) {
                            log.warn("Rate limit hit (attempt {}/{}), waiting 61s before retry...", attempt + 1, maxRetries)
                            eventPublisher.publishEvent(RateLimitHitEvent(this, attempt + 1, maxRetries))
                            Thread.sleep(61_000)
                        } else {
                            throw e
                        }
                    }
                    else -> throw e
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
            LlmProvider.DEEP_SEEK -> {
                val api = OpenAiApi.builder().apiKey(apiKey).baseUrl(deepseekBaseUrl).build() // DeepSeek is compatible with OpenAI API
                val options = OpenAiChatOptions.builder().model(deepseekModel).build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            LlmProvider.EXAONE -> {
                val api = OpenAiApi.builder().apiKey(apiKey).baseUrl(exaoneBaseUrl).build() // ExaOne is compatible with OpenAI API
                val options = OpenAiChatOptions.builder().model(exaoneModel).build()
                OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry)
            }
            LlmProvider.BEDROCK -> {
                bedrockChatModel ?: throw IllegalStateException("Amazon Bedrock LLM is not configured")
            }
        }
    }

    private fun buildBedrockChatModel(): BedrockProxyChatModel {
        val toolCallingManager = ToolCallingManager.builder().build()
        val observationRegistry = ObservationRegistry.NOOP

        val credentialsProvider = if (bedrockAccessKey.isNotBlank() && bedrockSecretKey.isNotBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(bedrockAccessKey, bedrockSecretKey))
        } else {
            DefaultCredentialsProvider.builder().build()
        }
        val region = Region.of(bedrockRegion)
        val syncClient = BedrockRuntimeClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()
        val asyncClient = BedrockRuntimeAsyncClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()
        val options = BedrockChatOptions.builder().model(bedrockModel).build()
        return BedrockProxyChatModel(syncClient, asyncClient, options, observationRegistry, toolCallingManager)
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

    fun executeAnalyzeFiring(alertSection: String, logSection: String, metricSection: String = "", sourceSection: String = ""): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert in analyzing application errors and logs. " +
                    "Analyze the provided Grafana alert context, application logs, metrics, and related source context when available, " +
                    "identify the root cause, related code locations, and give clear, actionable recommendations. " +
                    "Do not state unverifiable facts as certainties; express them as possibilities."
        )
        val userMessage = UserMessage(
            buildString {
                append(alertSection)
                appendLine()
                append(logSection)
                appendLine()
                if (metricSection.isNotBlank()) {
                    append(metricSection)
                    appendLine()
                }
                if (sourceSection.isNotBlank()) {
                    append(sourceSection)
                    appendLine()
                }
                appendLine("Based on the above alert${if (metricSection.isNotBlank()) ", metrics," else ""} logs${if (sourceSection.isNotBlank()) ", and related source context" else ""}, please provide in markdown format:")
                appendLine("1. Root cause analysis")
                appendLine("2. Affected components")
                appendLine("3. Related source files and line numbers")
                appendLine("4. Why the related code may have caused the incident")
                appendLine("5. Concrete fix guidance")
                appendLine("6. Recommended tests or verification steps")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
                appendLine()
                appendLine("Then, after the markdown report, append the exact delimiter and a JSON array of concrete source code change suggestions:")
                appendLine("---SOURCE_CODE_SUGGESTIONS_JSON_START---")
                appendLine("""[{"filePath":"relative/path/to/File.kt","originalCode":"exact original code lines","suggestionCode":"replacement code lines","description":"Why this change helps","lineNumber":42}]""")
                appendLine("---SOURCE_CODE_SUGGESTIONS_JSON_END---")
                appendLine()
                appendLine("Rules for the JSON section:")
                appendLine("- Use an empty array [] if no concrete source code change can be recommended.")
                appendLine("- 'filePath' must match a file path from the related source snippets when possible.")
                appendLine("- 'originalCode' and 'suggestionCode' must be concise and include only the relevant changed lines.")
                appendLine("- 'lineNumber' must be a number or null when unknown.")
                appendLine("- Output valid JSON only between the delimiters — no trailing commas, no comments.")
                appendLine("- CRITICAL: every string value must be valid JSON. Escape backslashes as \\\\, double-quotes as \\\", and newlines as \\n.")
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

    fun executeAnalyzeCodeDifferInline(codeReviewSection: String): String {
        val model = chatModel ?: return ""
        val systemMessage = SystemMessage(
            "You are an expert code reviewer performing an incremental review of a pull request. " +
                    "You will be given a code diff and must produce (1) a short overall summary and " +
                    "(2) a small number of high-value, line-anchored comments. " +
                    "Only comment on lines that appear in the diff. " +
                    "Prefer correctness, potential bugs, security, and concurrency over stylistic nitpicks. " +
                    "Do not state unverifiable facts as certainties; express them as possibilities."
        )
        val userMessage = UserMessage(
            buildString {
                append(codeReviewSection)
                appendLine()
                appendLine("First, write a short markdown summary of the overall change (a few bullets is fine).")
                if (resultLanguage != "en") {
                    appendLine(languageOptions)
                }
                appendLine()
                appendLine("Then, after the markdown summary, append the exact delimiter and a JSON array of inline comments:")
                appendLine("---INLINE_COMMENTS_JSON_START---")
                appendLine("""[{"file":"relative/path/to/File.kt","line":42,"side":"RIGHT","body":"**markdown** comment body"}]""")
                appendLine("---INLINE_COMMENTS_JSON_END---")
                appendLine()
                appendLine("Rules for the JSON section:")
                appendLine("- 'file': MUST exactly match one of the file paths shown in the diff (e.g. GitHub filename or GitLab new_path).")
                appendLine("- 'line': integer line number that appears in the diff. For added or unchanged lines use the NEW file line; for deleted lines use the OLD file line.")
                appendLine("- 'side': 'RIGHT' for added or unchanged lines in the new version, 'LEFT' for deleted lines in the previous version.")
                appendLine("- 'body': markdown-formatted actionable comment. Keep it concise (1–3 sentences).")
                appendLine("- Only include comments on lines that are actually present in the diff hunks above. Do NOT invent line numbers outside the diff.")
                appendLine("- Prefer fewer high-signal comments over many low-value ones. If there is nothing meaningful to say, use an empty array [].")
                appendLine("- Output valid JSON only between the delimiters — no trailing commas, no comments.")
                appendLine("- CRITICAL: every string value must be valid JSON. Escape backslashes as \\\\, double-quotes as \\\", and newlines as \\n.")
            }
        )
        llmRateLimiter.acquire()
        return try {
            callWithRateLimitRetry(model, Prompt(listOf(systemMessage, userMessage)))
        } finally {
            llmRateLimiter.release()
        }
    }
}
