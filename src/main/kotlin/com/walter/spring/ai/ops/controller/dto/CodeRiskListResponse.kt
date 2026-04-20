package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.CodeRiskRecord
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Paginated list of code risk analysis records for an application")
data class CodeRiskListResponse(
    @Schema(description = "List of code risk analysis records")
    val records: List<CodeRiskRecord>
)
