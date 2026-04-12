package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.LokiConfigRequest
import com.walter.spring.ai.ops.controller.dto.LokiConfigResponse
import com.walter.spring.ai.ops.controller.dto.LokiConfigured
import com.walter.spring.ai.ops.service.LokiService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Loki Configuration", description = "Loki base URL configuration for log queries")
@RestController
@RequestMapping("/api/loki")
class LokiConfigController(
    private val lokiService: LokiService,
) {
    @Operation(summary = "Get Loki configuration status")
    @GetMapping("/status")
    fun status(): LokiConfigured {
        val lokiUrl = lokiService.getLokiUrl()
        return LokiConfigured(lokiUrl.isNotBlank(), lokiUrl)
    }

    @Operation(
        summary = "Save Loki base URL",
        description = "Validates the connection to the given URL before persisting it in Redis."
    )
    @PostMapping("/config")
    fun configure(@RequestBody request: LokiConfigRequest): LokiConfigResponse {
        lokiService.setLokiUrl(request.url)
        return LokiConfigResponse.success()
    }
}