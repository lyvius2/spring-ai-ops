package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LLM_API_KEY
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

    private lateinit var aiModelService: AiModelService

    @BeforeEach
    fun setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps)
        given(cryptoProvider.encrypt(anyString())).willAnswer { it.getArgument(0) }
        given(cryptoProvider.decrypt(anyString())).willAnswer { it.getArgument(0) }
        aiModelService = buildService()
    }

    private fun buildService(openAiApiKey: String = "", anthropicApiKey: String = "") =
        AiModelService(redisTemplate, cryptoProvider, Semaphore(10), eventPublisher, "gpt-4o-mini", openAiApiKey, "claude-3-5-sonnet-20241022", anthropicApiKey, 4096, "en")

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 llm과 llmKey가 모두 있으면 initialize 후 ChatModel이 구성됨")
    fun initialize_configuresChatModel_whenRedisHasBothKeys() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn("openai")
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn("sk-fake-key")

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Redis에 llm 키가 없으면 initialize 후에도 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenLlmKeyMissing() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isFalse()
    }

    @Test
    @DisplayName("Redis에 llmKey가 없으면 initialize 후에도 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenApiKeyMissing() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn("openai")
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isFalse()
    }

    // ── configure ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("openai provider로 configure하면 ChatModel이 생성되고 Redis에 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenOpenAi() {
        aiModelService.configure(LlmProvider.OPEN_AI, "sk-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set(REDIS_KEY_LLM, "openai")
        verify(valueOps).set(REDIS_KEY_LLM_API_KEY, "sk-fake-key")
    }

    @Test
    @DisplayName("anthropic provider로 configure하면 ChatModel이 생성되고 Redis에 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenAnthropic() {
        aiModelService.configure(LlmProvider.ANTHROPIC, "sk-ant-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set(REDIS_KEY_LLM, "anthropic")
        verify(valueOps).set(REDIS_KEY_LLM_API_KEY, "sk-ant-fake-key")
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
    @DisplayName("Redis에 llm 값이 있으면 해당 값 반환")
    fun getCurrentLlm_returnsLlmValue_whenPresentInRedis() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn("openai")

        assertThat(aiModelService.getCurrentLlm()).isEqualTo("openai")
    }

    @Test
    @DisplayName("Redis에 llm 값이 없으면 null 반환")
    fun getCurrentLlm_returnsNull_whenAbsentInRedis() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)

        assertThat(aiModelService.getCurrentLlm()).isNull()
    }

    // ── configure (blank apiKey — reconfigure) ────────────────────────────────

    @Test
    @DisplayName("apiKey가 blank이고 Redis에 기존 키가 있으면 기존 키로 configure됨")
    fun configure_usesExistingKey_whenApiKeyIsBlankAndRedisHasKey() {
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn("sk-saved-key")

        aiModelService.configure(LlmProvider.OPEN_AI, "")

        assertThat(aiModelService.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("apiKey가 blank이고 Redis에도 키가 없으면 IllegalStateException 발생")
    fun configure_throwsIllegalStateException_whenApiKeyIsBlankAndNoExistingKey() {
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)

        assertThatThrownBy { aiModelService.configure(LlmProvider.OPEN_AI, "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("API key is not configured")
    }

    // ── getChatModel ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ChatModel이 구성되지 않은 상태에서 getChatModel은 예외 발생")
    fun getChatModel_throwsException_whenNotConfigured() {
        assertThatThrownBy { aiModelService.getChatModel() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("configure 후 getChatModel은 ChatModel 인스턴스 반환")
    fun getChatModel_returnsChatModel_afterConfigure() {
        aiModelService.configure(LlmProvider.OPEN_AI, "sk-fake-key")

        assertThat(aiModelService.getChatModel()).isNotNull()
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
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)
        val service = buildService(openAiApiKey = "sk-yml-key")

        service.initialize()

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Anthropic yml 키만 있으면 initialize 시 Anthropic으로 자동 설정됨")
    fun initialize_autoConfiguresAnthropic_whenOnlyAnthropicYmlKeyPresent() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)
        val service = buildService(anthropicApiKey = "sk-ant-yml-key")

        service.initialize()

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("두 yml 키가 모두 있으면 initialize 시 자동 설정되지 않음 (Provider 선택 필요)")
    fun initialize_doesNotAutoConfigure_whenBothYmlKeysPresent() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")

        service.initialize()

        assertThat(service.isConfigured()).isFalse()
    }

    // ── isSelectProviderRequired ──────────────────────────────────────────────

    @Test
    @DisplayName("두 yml 키 모두 있고 ChatModel 미설정이면 isSelectProviderRequired는 true")
    fun isSelectProviderRequired_returnsTrue_whenBothYmlKeysAndNotConfigured() {
        given(valueOps.get(REDIS_KEY_LLM)).willReturn(null)
        given(valueOps.get(REDIS_KEY_LLM_API_KEY)).willReturn(null)
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")

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
