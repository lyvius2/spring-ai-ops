package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "List of Grafana alert firing analysis records for an application")
data class FiringListResponse(
    @Schema(description = "List of firing analysis records ordered by alert time (newest first)")
    val firings: List<AnalyzeFiringRecord>
)
