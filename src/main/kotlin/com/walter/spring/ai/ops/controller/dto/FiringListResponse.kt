package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.AnalyzeFiringRecord

data class FiringListResponse(
    val firings: List<AnalyzeFiringRecord>
)
