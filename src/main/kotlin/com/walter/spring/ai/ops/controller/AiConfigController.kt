package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.ConnectionStatus
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.controller.dto.AiConfigRequest
import com.walter.spring.ai.ops.controller.dto.AiConfigResponse
import com.walter.spring.ai.ops.controller.dto.LlmStatusResponse
import com.walter.spring.ai.ops.controller.dto.SelectProviderRequest
import com.walter.spring.ai.ops.service.AiModelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
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
        summary = "Get LLM configuration status",
        description = "Returns the currently active provider key, whether LLM is configured, and the list of provider keys that have a stored API key."
    )
    @GetMapping("/status")
    fun getStatus(): LlmStatusResponse {
        val savedProviders = LlmProvider.entries
            .filter { aiModelService.hasApiKey(it) }
            .map { it.key }
        return LlmStatusResponse(
            usageLlm = aiModelService.getCurrentLlm(),
            configured = aiModelService.isConfigured(),
            savedProviders = savedProviders,
        )
    }

    @Operation(
        summary = "Save LLM provider and API key",
        description = "Stores the API key for the given provider and activates it as the current LLM. Returns SUCCESS if the model initialises correctly."
    )
    @PostMapping("/config")
    fun configure(@RequestBody request: AiConfigRequest): AiConfigResponse {
        val provider = LlmProvider.fromKey(request.llm)
        aiModelService.configure(provider, request.apiKey)
        return AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm)
    }

    @Operation(
        summary = "Switch active LLM provider",
        description = "Activates a different provider from the ones already stored in Redis (or application.yml). Does not require re-entering the API key if it was previously saved."
    )
    @PostMapping("/select-provider")
    fun selectProvider(@RequestBody request: SelectProviderRequest): AiConfigResponse {
        return try {
            val provider = LlmProvider.fromKey(request.llm)
            val hasStoredKey = aiModelService.hasApiKey(provider)
            if (hasStoredKey) {
                aiModelService.configure(provider, "")
            } else {
                aiModelService.configureFromYml(provider)
            }
            AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm)
        } catch (e: Exception) {
            AiConfigResponse.error(e.message)
        }
    }
}
