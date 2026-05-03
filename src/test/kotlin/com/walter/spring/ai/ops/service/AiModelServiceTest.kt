package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM_APIS
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_USAGE_LLM
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.util.CryptoProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.util.concurrent.Semaphore

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiModelServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var cryptoProvider: CryptoProvider
    @Mock private lateinit var valueOps: ValueOperations<String, String>
    @Mock private lateinit var mockChatModel: ChatModel
    @Mock private lateinit var eventPublisher: ApplicationEventPublisher

    private val objectMapper = ObjectMapper().findAndRegisterModules()

    private lateinit var aiModelService: AiModelService

    @BeforeEach
    fun setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps)
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
        // Default: no saved LLM configs in Redis
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(null)
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn(null)
        aiModelService = buildService()
    }

    private fun buildService(openAiApiKey: String = "", anthropicApiKey: String = "", deepseekApiKey: String = "") =
        AiModelService(
            redisTemplate, cryptoProvider, objectMapper, Semaphore(10), eventPublisher,
            "gpt-4o-mini", openAiApiKey,
            "claude-sonnet-4-6", anthropicApiKey, 4096,
            "deepseek-v4-pro", deepseekApiKey, "https://api.deepseek.com",
            "en"
        )

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 usageLlm과 llmApis가 있으면 initialize 후 ChatModel이 구성됨")
    fun initialize_configuresChatModel_whenRedisHasUsageLlmAndApis() {
        // given
        val configs = """[{"provider":"OPEN_AI","apiKey":"sk-fake-key"}]"""
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(configs)
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn("openai")

        // when
        aiModelService.initialize()

        // then
        assertThat(aiModelService.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Redis에 usageLlm이 없으면 initialize 후에도 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenUsageLlmMissing() {
        // given
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn(null)

        // when
        aiModelService.initialize()

        // then
        assertThat(aiModelService.isConfigured()).isFalse()
    }

    @Test
    @DisplayName("Redis에 usageLlm은 있지만 해당 provider의 apiKey가 없으면 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenMatchedApiKeyIsBlank() {
        // given
        val configs = """[{"provider":"OPEN_AI","apiKey":""}]"""
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(configs)
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn("openai")

        // when
        aiModelService.initialize()

        // then
        assertThat(aiModelService.isConfigured()).isFalse()
    }

    // ── configure ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("openai provider로 configure하면 ChatModel이 생성되고 Redis에 llmApis와 usageLlm이 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenOpenAi() {
        // when
        aiModelService.configure(LlmProvider.OPEN_AI, "sk-fake-key")

        // then
        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set(eq(REDIS_KEY_LLM_APIS), anyString())
        verify(valueOps).set(REDIS_KEY_USAGE_LLM, "openai")
    }

    @Test
    @DisplayName("anthropic provider로 configure하면 ChatModel이 생성되고 Redis에 usageLlm이 anthropic으로 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenAnthropic() {
        // when
        aiModelService.configure(LlmProvider.ANTHROPIC, "sk-ant-fake-key")

        // then
        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set(REDIS_KEY_USAGE_LLM, "anthropic")
    }

    // ── isConfigured ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("초기 상태에서 isConfigured는 false")
    fun isConfigured_returnsFalse_initially() {
        assertThat(aiModelService.isConfigured()).isFalse()
    }

    @Test
    @DisplayName("configure 후 isConfigured는 true")
    fun isConfigured_returnsTrue_afterConfigure() {
        aiModelService.configure(LlmProvider.OPEN_AI, "sk-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
    }

    // ── getCurrentLlm ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 usageLlm 값이 있으면 해당 값 반환")
    fun getCurrentLlm_returnsLlmValue_whenPresentInRedis() {
        // given
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn("openai")

        // when / then
        assertThat(aiModelService.getCurrentLlm()).isEqualTo("openai")
    }

    @Test
    @DisplayName("Redis에 usageLlm 값이 없으면 null 반환")
    fun getCurrentLlm_returnsNull_whenAbsentInRedis() {
        // given
        given(valueOps.get(REDIS_KEY_USAGE_LLM)).willReturn(null)

        // when / then
        assertThat(aiModelService.getCurrentLlm()).isNull()
    }

    // ── configure (blank apiKey — reuse stored key) ────────────────────────────

    @Test
    @DisplayName("apiKey가 blank이고 Redis llmApis에 기존 키가 있으면 기존 키로 configure됨")
    fun configure_usesExistingKey_whenApiKeyIsBlankAndRedisHasKey() {
        // given
        val configs = """[{"provider":"OPEN_AI","apiKey":"sk-saved-key"}]"""
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(configs)

        // when
        aiModelService.configure(LlmProvider.OPEN_AI, "")

        // then
        assertThat(aiModelService.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("apiKey가 blank이고 Redis에도 키가 없으면 IllegalStateException 발생")
    fun configure_throwsIllegalStateException_whenApiKeyIsBlankAndNoExistingKey() {
        // given
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(null)

        // when / then
        assertThatThrownBy { aiModelService.configure(LlmProvider.OPEN_AI, "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("API key is not configured")
    }

    // ── hasApiKey ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("llmApis에 해당 provider의 apiKey가 저장돼 있으면 hasApiKey는 true")
    fun hasApiKey_returnsTrue_whenProviderKeyIsSaved() {
        // given
        val configs = """[{"provider":"OPEN_AI","apiKey":"sk-saved"}]"""
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(configs)

        // when / then
        assertThat(aiModelService.hasApiKey(LlmProvider.OPEN_AI)).isTrue()
    }

    @Test
    @DisplayName("llmApis에 해당 provider가 없으면 hasApiKey는 false")
    fun hasApiKey_returnsFalse_whenProviderNotPresent() {
        // given
        given(valueOps.get(REDIS_KEY_LLM_APIS)).willReturn(null)

        // when / then
        assertThat(aiModelService.hasApiKey(LlmProvider.OPEN_AI)).isFalse()
    }

    // ── executeAnalyzeFiring ──────────────────────────────────────────────────

    @Test
    @DisplayName("ChatModel이 null이면 executeAnalyzeFiring은 빈 문자열 반환")
    fun executeAnalyzeFiring_returnsEmpty_whenChatModelIsNull() {
        val result = aiModelService.executeAnalyzeFiring("## Alert", "## Logs")

        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("ChatModel이 구성된 경우 LLM 분석 결과 문자열 반환")
    fun executeAnalyzeFiring_returnsAnalysisResult_whenChatModelIsConfigured() {
        injectChatModel(mockChatModel)
        val response = mockChatResponse("Root cause: NPE in PaymentService")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)

        val result = aiModelService.executeAnalyzeFiring("## Alert", "## Logs")

        assertThat(result).isEqualTo("Root cause: NPE in PaymentService")
    }

    @Test
    @DisplayName("alertSection과 logSection이 프롬프트에 포함되어 LLM에 전달됨")
    fun executeAnalyzeFiring_includesBothSectionsInPrompt() {
        injectChatModel(mockChatModel)
        val response = mockChatResponse("analysis")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)

        aiModelService.executeAnalyzeFiring("## Alert\nTitle: test-alert", "## Logs\nERROR: timeout")

        verify(mockChatModel).call(any(Prompt::class.java))
    }

    @Test
    @DisplayName("sourceSection이 있으면 프롬프트에 source context와 suggestion JSON 계약을 포함함")
    fun givenSourceSection_whenExecuteAnalyzeFiring_thenPromptIncludesSourceContextAndSuggestionJsonContract() {
        // given
        injectChatModel(mockChatModel)
        val response = mockChatResponse("analysis")
        var capturedPrompt: Prompt? = null
        given(mockChatModel.call(any(Prompt::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.arguments[0] as Prompt
            response
        }

        // when
        aiModelService.executeAnalyzeFiring(
            alertSection = "## Alert",
            logSection = "## Logs",
            sourceSection = "## Related source snippets\nFile: src/main/kotlin/FooService.kt",
        )

        // then
        val promptText = requireNotNull(capturedPrompt).instructions.joinToString(System.lineSeparator()) { it.text }
        assertThat(promptText).contains("## Related source snippets")
        assertThat(promptText).contains("File: src/main/kotlin/FooService.kt")
        assertThat(promptText).contains("---SOURCE_CODE_SUGGESTIONS_JSON_START---")
        assertThat(promptText).contains("---SOURCE_CODE_SUGGESTIONS_JSON_END---")
        assertThat(promptText).contains("filePath")
        assertThat(promptText).contains("suggestionCode")
    }

    // ── executeAnalyzeCodeDiffer ──────────────────────────────────────────────

    @Test
    @DisplayName("ChatModel이 null이면 executeAnalyzeCodeDiffer는 빈 문자열 반환")
    fun executeAnalyzeCodeDiffer_returnsEmpty_whenChatModelIsNull() {
        val result = aiModelService.executeAnalyzeCodeDiffer(GithubCompareResult().createCodeReviewPrompt())

        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("ChatModel이 구성된 경우 코드 리뷰 결과 문자열 반환")
    fun executeAnalyzeCodeDiffer_returnsReviewResult_whenChatModelIsConfigured() {
        injectChatModel(mockChatModel)
        val response = mockChatResponse("## Code Review\nLooks good overall.")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)

        val result = aiModelService.executeAnalyzeCodeDiffer(GithubCompareResult().createCodeReviewPrompt())

        assertThat(result).isEqualTo("## Code Review\nLooks good overall.")
    }

    @Test
    @DisplayName("compareResult의 파일 정보가 프롬프트에 포함되어 LLM에 전달됨")
    fun executeAnalyzeCodeDiffer_includesFileInfoInPrompt() {
        injectChatModel(mockChatModel)
        val response = mockChatResponse("review")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)
        val compareResult = GithubCompareResult(
            files = listOf(
                GithubFile(filename = "src/Main.kt", status = "modified", additions = 3, deletions = 1, patch = "@@ -1 +1 @@\n-old\n+new")
            )
        )

        aiModelService.executeAnalyzeCodeDiffer(compareResult.createCodeReviewPrompt())

        verify(mockChatModel).call(any(Prompt::class.java))
    }

    // ── initialize (yml auto-configure) ──────────────────────────────────────

    @Test
    @DisplayName("OpenAI yml 키만 있으면 initialize 시 OpenAI로 자동 설정됨")
    fun initialize_autoConfiguresOpenAi_whenOnlyOpenAiYmlKeyPresent() {
        // given
        val service = buildService(openAiApiKey = "sk-yml-key")

        // when
        service.initialize()

        // then
        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Anthropic yml 키만 있으면 initialize 시 Anthropic으로 자동 설정됨")
    fun initialize_autoConfiguresAnthropic_whenOnlyAnthropicYmlKeyPresent() {
        // given
        val service = buildService(anthropicApiKey = "sk-ant-yml-key")

        // when
        service.initialize()

        // then
        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("두 yml 키가 모두 있으면 initialize 시 자동 설정되지 않음 (Provider 선택 필요)")
    fun initialize_doesNotAutoConfigure_whenBothYmlKeysPresent() {
        // given
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")

        // when
        service.initialize()

        // then
        assertThat(service.isConfigured()).isFalse()
    }

    // ── isSelectProviderRequired ──────────────────────────────────────────────

    @Test
    @DisplayName("두 yml 키 모두 있고 ChatModel 미설정이면 isSelectProviderRequired는 true")
    fun isSelectProviderRequired_returnsTrue_whenBothYmlKeysAndNotConfigured() {
        // given
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")

        // when / then
        assertThat(service.isSelectProviderRequired()).isTrue()
    }

    @Test
    @DisplayName("yml 키가 하나만 있으면 isSelectProviderRequired는 false")
    fun isSelectProviderRequired_returnsFalse_whenOnlyOneYmlKey() {
        val service = buildService(openAiApiKey = "sk-openai")

        assertThat(service.isSelectProviderRequired()).isFalse()
    }

    @Test
    @DisplayName("두 yml 키 모두 있어도 ChatModel이 이미 설정되면 isSelectProviderRequired는 false")
    fun isSelectProviderRequired_returnsFalse_whenAlreadyConfigured() {
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")
        service.configure(LlmProvider.OPEN_AI, "sk-openai")

        assertThat(service.isSelectProviderRequired()).isFalse()
    }

    // ── configureFromYml ──────────────────────────────────────────────────────

    @Test
    @DisplayName("configureFromYml(OPEN_AI) 호출 시 yml의 OpenAI 키로 ChatModel이 설정됨")
    fun configureFromYml_configuresOpenAi_whenOpenAiProviderSelected() {
        val service = buildService(openAiApiKey = "sk-yml-openai", anthropicApiKey = "sk-yml-ant")

        service.configureFromYml(LlmProvider.OPEN_AI)

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("configureFromYml(ANTHROPIC) 호출 시 yml의 Anthropic 키로 ChatModel이 설정됨")
    fun configureFromYml_configuresAnthropic_whenAnthropicProviderSelected() {
        val service = buildService(openAiApiKey = "sk-yml-openai", anthropicApiKey = "sk-yml-ant")

        service.configureFromYml(LlmProvider.ANTHROPIC)

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("configureFromYml 시 yml 키가 비어있으면 IllegalStateException 발생")
    fun configureFromYml_throwsIllegalStateException_whenYmlKeyIsBlank() {
        val service = buildService(openAiApiKey = "")

        assertThatThrownBy { service.configureFromYml(LlmProvider.OPEN_AI) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured in application.yml")
    }

    // ── executeAnalyzeCodeRisk ────────────────────────────────────────────────

    @Test
    @DisplayName("ChatModel이 null이면 executeAnalyzeCodeRisk는 빈 문자열 반환")
    fun executeAnalyzeCodeRisk_returnsEmpty_whenChatModelIsNull() {
        // given
        val bundle = "## File: Service.kt\n```\nclass Service\n```"

        // when
        val result = aiModelService.executeAnalyzeCodeRisk(bundle)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("ChatModel이 구성된 경우 코드 리스크 분석 결과 문자열 반환")
    fun executeAnalyzeCodeRisk_returnsAnalysisResult_whenChatModelIsConfigured() {
        // given
        injectChatModel(mockChatModel)
        val response = mockChatResponse("## Security vulnerabilities\nNo issues found.")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)

        // when
        val result = aiModelService.executeAnalyzeCodeRisk("## File: Service.kt\n```\nclass Service\n```")

        // then
        assertThat(result).isEqualTo("## Security vulnerabilities\nNo issues found.")
    }

    // ── executeFinalAnalyzeCode ───────────────────────────────────────────────

    @Test
    @DisplayName("ChatModel이 null이면 executeFinalAnalyzeCode는 빈 문자열 반환")
    fun executeFinalAnalyzeCode_returnsEmpty_whenChatModelIsNull() {
        // given
        val issues = listOf("## Security vulnerabilities\nSQL injection in UserRepo")

        // when
        val result = aiModelService.executeFinalAnalyzeCode(issues)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("issues 목록이 비어있으면 executeFinalAnalyzeCode는 빈 문자열 반환")
    fun executeFinalAnalyzeCode_returnsEmpty_whenIssuesIsEmpty() {
        // given
        injectChatModel(mockChatModel)

        // when
        val result = aiModelService.executeFinalAnalyzeCode(emptyList())

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("ChatModel이 구성된 경우 최종 종합 보고서 문자열 반환")
    fun executeFinalAnalyzeCode_returnsFinalReport_whenChatModelIsConfigured() {
        // given
        injectChatModel(mockChatModel)
        val response = mockChatResponse("## Executive summary\nOverall risk is medium.")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)
        val issues = listOf("issue from service chunk", "issue from controller chunk")

        // when
        val result = aiModelService.executeFinalAnalyzeCode(issues)

        // then
        assertThat(result).isEqualTo("## Executive summary\nOverall risk is medium.")
    }

    @Test
    @DisplayName("모든 청크 이슈가 하나의 프롬프트에 합산되어 LLM에 전달됨")
    fun executeFinalAnalyzeCode_combinesAllIssuesIntoSinglePrompt() {
        // given
        injectChatModel(mockChatModel)
        val response = mockChatResponse("final report")
        given(mockChatModel.call(any(Prompt::class.java))).willReturn(response)
        val issues = listOf("Analysis Part 1 content", "Analysis Part 2 content")

        // when
        aiModelService.executeFinalAnalyzeCode(issues)

        // then
        verify(mockChatModel).call(any(Prompt::class.java))
    }

    // ── estimateTokenCount ────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 문자열이면 estimateTokenCount는 0 반환")
    fun estimateTokenCount_returnsZero_whenBundleIsEmpty() {
        // given
        val bundle = ""

        // when
        val result = aiModelService.estimateTokenCount(bundle)

        // then
        assertThat(result).isEqualTo(0)
    }

    @Test
    @DisplayName("ASCII 문자만 있으면 estimateTokenCount는 length / 4 반환")
    fun estimateTokenCount_returnsLengthDividedByFour_whenAllAscii() {
        // given
        val bundle = "a".repeat(100)

        // when
        val result = aiModelService.estimateTokenCount(bundle)

        // then
        assertThat(result).isEqualTo(25)
    }

    @Test
    @DisplayName("비ASCII 문자(한글 등)는 1자당 1토큰으로 계산됨")
    fun estimateTokenCount_countsNonAsciiAsOneTokenEach() {
        // given
        val bundle = "가".repeat(50)

        // when
        val result = aiModelService.estimateTokenCount(bundle)

        // then
        assertThat(result).isEqualTo(50)
    }

    @Test
    @DisplayName("ASCII와 비ASCII가 혼합된 경우 각각의 규칙이 합산됨")
    fun estimateTokenCount_sumsBothRules_whenMixedContent() {
        // given
        val bundle = "a".repeat(80) + "가".repeat(10) // 80/4 + 10 = 30

        // when
        val result = aiModelService.estimateTokenCount(bundle)

        // then
        assertThat(result).isEqualTo(30)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun injectChatModel(chatModel: ChatModel) {
        val field = AiModelService::class.java.getDeclaredField("chatModel")
        field.isAccessible = true
        field.set(aiModelService, chatModel)
    }

    private fun mockChatResponse(text: String): ChatResponse {
        val response = mock(ChatResponse::class.java)
        given(response.result).willReturn(Generation(AssistantMessage(text)))
        return response
    }
}
