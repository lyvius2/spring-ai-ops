package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.record.CodeRiskRecord

data class CodeRiskListResponse(
    val records: List<CodeRiskRecord>
)
