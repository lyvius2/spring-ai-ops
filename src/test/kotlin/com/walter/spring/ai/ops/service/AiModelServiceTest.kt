package com.walter.spring.ai.ops.service

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
        aiModelService = AiModelService(redisTemplate, "gpt-4o-mini", "claude-3-5-sonnet-20241022")
    }

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
