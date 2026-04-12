package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.FiringListResponse
import com.walter.spring.ai.ops.service.GrafanaService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/firing")
class FiringController(
    private val grafanaService: GrafanaService,
) {
    @GetMapping("/{application}/list")
    fun list(@PathVariable application: String): FiringListResponse {
        return FiringListResponse(grafanaService.getAnalyzeFiringRecords(application))
    }
}