package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.CodeReviewRecord

data class CommitListResponse(
    val commits: List<CodeReviewRecord>
)
