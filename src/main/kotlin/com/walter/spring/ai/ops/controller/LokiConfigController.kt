package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.LokiConfigRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/loki")
class LokiConfigController(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${loki.url:}") private val lokiUrlFromConfig: String,
) {
    @GetMapping("/status")
    fun status(): ResponseEntity<Map<String, Any>> {
        val configured = lokiUrlFromConfig.isNotBlank()
            || redisTemplate.opsForValue().get("lokiUrl")?.isNotBlank() == true
        return ResponseEntity.ok(mapOf("configured" to configured))
    }

    @PostMapping("/config")
    fun configure(@RequestBody request: LokiConfigRequest): ResponseEntity<Map<String, String>> {
        redisTemplate.opsForValue().set("lokiUrl", request.url)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }
}