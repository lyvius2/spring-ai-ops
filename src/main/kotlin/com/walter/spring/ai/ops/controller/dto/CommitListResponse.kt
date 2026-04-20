package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.CodeReviewRecord
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "List of AI code review records for an application")
data class CommitListResponse(
    @Schema(description = "List of code review records ordered by commit time (newest first)")
    val commits: List<CodeReviewRecord>
)
