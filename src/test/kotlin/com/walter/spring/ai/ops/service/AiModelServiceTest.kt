package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiModelServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var valueOps: ValueOperations<String, String>
    @Mock private lateinit var mockChatModel: ChatModel

    private lateinit var aiModelService: AiModelService

    @BeforeEach
    fun setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps)
        aiModelService = buildService()
    }

    private fun buildService(openAiApiKey: String = "", anthropicApiKey: String = "") =
        AiModelService(redisTemplate, "gpt-4o-mini", openAiApiKey, "claude-3-5-sonnet-20241022", anthropicApiKey, "en")

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 llm과 llmKey가 모두 있으면 initialize 후 ChatModel이 구성됨")
    fun initialize_configuresChatModel_whenRedisHasBothKeys() {
        given(valueOps.get("llm")).willReturn("openai")
        given(valueOps.get("llmKey")).willReturn("sk-fake-key")

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Redis에 llm 키가 없으면 initialize 후에도 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenLlmKeyMissing() {
        given(valueOps.get("llm")).willReturn(null)

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isFalse()
    }

    @Test
    @DisplayName("Redis에 llmKey가 없으면 initialize 후에도 ChatModel이 구성되지 않음")
    fun initialize_doesNotConfigureChatModel_whenApiKeyMissing() {
        given(valueOps.get("llm")).willReturn("openai")
        given(valueOps.get("llmKey")).willReturn(null)

        aiModelService.initialize()

        assertThat(aiModelService.isConfigured()).isFalse()
    }

    // ── configure ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("openai provider로 configure하면 ChatModel이 생성되고 Redis에 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenOpenAi() {
        aiModelService.configure("openai", "sk-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set("llm", "openai")
        verify(valueOps).set("llmKey", "sk-fake-key")
    }

    @Test
    @DisplayName("anthropic provider로 configure하면 ChatModel이 생성되고 Redis에 저장됨")
    fun configure_createsChatModel_andSavesToRedis_whenAnthropic() {
        aiModelService.configure("anthropic", "sk-ant-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
        verify(valueOps).set("llm", "anthropic")
        verify(valueOps).set("llmKey", "sk-ant-fake-key")
    }

    @Test
    @DisplayName("알 수 없는 provider로 configure하면 IllegalArgumentException 발생")
    fun configure_throwsIllegalArgumentException_whenUnknownProvider() {
        assertThatThrownBy { aiModelService.configure("unknown-llm", "fake-key") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown LLM provider")
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
        aiModelService.configure("openai", "sk-fake-key")

        assertThat(aiModelService.isConfigured()).isTrue()
    }

    // ── getCurrentLlm ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 llm 값이 있으면 해당 값 반환")
    fun getCurrentLlm_returnsLlmValue_whenPresentInRedis() {
        given(valueOps.get("llm")).willReturn("openai")

        assertThat(aiModelService.getCurrentLlm()).isEqualTo("openai")
    }

    @Test
    @DisplayName("Redis에 llm 값이 없으면 null 반환")
    fun getCurrentLlm_returnsNull_whenAbsentInRedis() {
        given(valueOps.get("llm")).willReturn(null)

        assertThat(aiModelService.getCurrentLlm()).isNull()
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
        aiModelService.configure("openai", "sk-fake-key")

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
        given(valueOps.get("llm")).willReturn(null)
        given(valueOps.get("llmKey")).willReturn(null)
        val service = buildService(openAiApiKey = "sk-yml-key")

        service.initialize()

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("Anthropic yml 키만 있으면 initialize 시 Anthropic으로 자동 설정됨")
    fun initialize_autoConfiguresAnthropic_whenOnlyAnthropicYmlKeyPresent() {
        given(valueOps.get("llm")).willReturn(null)
        given(valueOps.get("llmKey")).willReturn(null)
        val service = buildService(anthropicApiKey = "sk-ant-yml-key")

        service.initialize()

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("두 yml 키가 모두 있으면 initialize 시 자동 설정되지 않음 (Provider 선택 필요)")
    fun initialize_doesNotAutoConfigure_whenBothYmlKeysPresent() {
        given(valueOps.get("llm")).willReturn(null)
        given(valueOps.get("llmKey")).willReturn(null)
        val service = buildService(openAiApiKey = "sk-openai", anthropicApiKey = "sk-ant")

        service.initialize()

        assertThat(service.isConfigured()).isFalse()
    }

    // ── isSelectProviderRequired ──────────────────────────────────────────────

    @Test
    @DisplayName("두 yml 키 모두 있고 ChatModel 미설정이면 isSelectProviderRequired는 true")
    fun isSelectProviderRequired_returnsTrue_whenBothYmlKeysAndNotConfigured() {
        given(valueOps.get("llm")).willReturn(null)
        given(valueOps.get("llmKey")).willReturn(null)
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
        service.configure("openai", "sk-openai")

        assertThat(service.isSelectProviderRequired()).isFalse()
    }

    // ── configureFromYml ──────────────────────────────────────────────────────

    @Test
    @DisplayName("configureFromYml(openai) 호출 시 yml의 OpenAI 키로 ChatModel이 설정됨")
    fun configureFromYml_configuresOpenAi_whenOpenAiProviderSelected() {
        val service = buildService(openAiApiKey = "sk-yml-openai", anthropicApiKey = "sk-yml-ant")

        service.configureFromYml("openai")

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("configureFromYml(anthropic) 호출 시 yml의 Anthropic 키로 ChatModel이 설정됨")
    fun configureFromYml_configuresAnthropic_whenAnthropicProviderSelected() {
        val service = buildService(openAiApiKey = "sk-yml-openai", anthropicApiKey = "sk-yml-ant")

        service.configureFromYml("anthropic")

        assertThat(service.isConfigured()).isTrue()
    }

    @Test
    @DisplayName("configureFromYml 시 yml 키가 비어있으면 IllegalStateException 발생")
    fun configureFromYml_throwsIllegalStateException_whenYmlKeyIsBlank() {
        val service = buildService(openAiApiKey = "")

        assertThatThrownBy { service.configureFromYml("openai") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured in application.yml")
    }

    @Test
    @DisplayName("configureFromYml 시 알 수 없는 provider이면 IllegalArgumentException 발생")
    fun configureFromYml_throwsIllegalArgumentException_whenUnknownProvider() {
        val service = buildService(openAiApiKey = "sk-key")

        assertThatThrownBy { service.configureFromYml("unknown") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown LLM provider")
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
