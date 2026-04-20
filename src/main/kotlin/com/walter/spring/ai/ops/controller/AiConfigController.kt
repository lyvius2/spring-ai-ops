package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.ConnectionStatus
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.controller.dto.AiConfigRequest
import com.walter.spring.ai.ops.controller.dto.AiConfigResponse
import com.walter.spring.ai.ops.controller.dto.SelectProviderRequest
import com.walter.spring.ai.ops.service.AiModelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "LLM Configuration", description = "LLM provider and API key management")
@RestController
@RequestMapping("/api/llm")
class AiConfigController(
    private val aiModelService: AiModelService,
) {
    @Operation(
        summary = "Save LLM provider and API key",
        description = "Configures the active LLM provider (openai / anthropic / groq) and stores the API key in Redis. Returns SUCCESS if the model initialises correctly."
    )
    @PostMapping("/config")
    fun configure(@RequestBody request: AiConfigRequest): AiConfigResponse {
        val provider = LlmProvider.fromKey(request.llm)
        aiModelService.configure(provider, request.apiKey)
        return AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm)
    }

    @Operation(
        summary = "Select provider from pre-configured yml keys",
        description = "When multiple LLM API keys (OpenAI / Anthropic / Groq) are present in application.yml, selects which provider to activate without re-entering the key."
    )
    @PostMapping("/select-provider")
    fun selectProvider(@RequestBody request: SelectProviderRequest): AiConfigResponse {
        return try {
            val provider = LlmProvider.fromKey(request.llm)
            aiModelService.configureFromYml(provider)
            AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm)
        } catch (e: Exception) {
            AiConfigResponse.error(e.message)
        }
    }
}
