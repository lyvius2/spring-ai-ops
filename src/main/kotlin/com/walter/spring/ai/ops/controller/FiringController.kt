package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.FiringListResponse
import com.walter.spring.ai.ops.service.GrafanaService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Alert Analysis", description = "Retrieve LLM-generated error analysis records per application")
@RestController
@RequestMapping("/api/firing")
class FiringController(
    private val grafanaService: GrafanaService,
) {
    @Operation(
        summary = "List alert analysis records for an application",
        description = "Returns the most recent `maximum-view-count` records (newest first). Records are kept for `data-retention-hours` hours."
    )
    @GetMapping("/{application}/list")
    fun list(
        @Parameter(description = "Registered application name", required = true)
        @PathVariable application: String
    ): FiringListResponse {
        return FiringListResponse(grafanaService.getAnalyzeFiringRecords(application))
    }
}