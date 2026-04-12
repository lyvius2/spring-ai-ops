package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.ConnectionStatus
import com.walter.spring.ai.ops.controller.dto.AiConfigRequest
import com.walter.spring.ai.ops.controller.dto.AiConfigResponse
import com.walter.spring.ai.ops.controller.dto.SelectProviderRequest
import com.walter.spring.ai.ops.service.AiModelService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/llm")
class AiConfigController(
    private val aiModelService: AiModelService,
) {
    @PostMapping("/config")
    fun configure(@RequestBody request: AiConfigRequest): ResponseEntity<AiConfigResponse> {
        return try {
            aiModelService.configure(request.llm, request.apiKey)
            ResponseEntity.ok(AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(AiConfigResponse.error(e.message))
        }
    }

    @PostMapping("/select-provider")
    fun selectProvider(@RequestBody request: SelectProviderRequest): AiConfigResponse {
        return try {
            aiModelService.configureFromYml(request.llm)
            AiConfigResponse.of(ConnectionStatus.SUCCESS, request.llm)
        } catch (e: Exception) {
            AiConfigResponse.error(e.message)
        }
    }
}
