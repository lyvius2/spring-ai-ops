package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.LokiConfigRequest
import com.walter.spring.ai.ops.controller.dto.LokiConfigResponse
import com.walter.spring.ai.ops.controller.dto.LokiConfigured
import com.walter.spring.ai.ops.service.LokiService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/loki")
class LokiConfigController(
    private val lokiService: LokiService,
) {
    @GetMapping("/status")
    fun status(): LokiConfigured {
        val lokiUrl = lokiService.getLokiUrl()
        return LokiConfigured(lokiUrl.isNotBlank(), lokiUrl)
    }

    @PostMapping("/config")
    fun configure(@RequestBody request: LokiConfigRequest): LokiConfigResponse {
        lokiService.setLokiUrl(request.url)
        return LokiConfigResponse.success()
    }
}