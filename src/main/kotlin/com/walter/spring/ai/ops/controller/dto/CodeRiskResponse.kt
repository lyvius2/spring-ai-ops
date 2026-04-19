package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.walter.spring.ai.ops.record.CodeRiskRecord

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeRiskResponse(
    val success: Boolean,
    val message: String,
    val data: CodeRiskRecord? = null,
) {
    companion object {
        fun success(data: CodeRiskRecord) = CodeRiskResponse(true, "Static analysis completed successfully.", data)
        fun failure(e: Exception) = CodeRiskResponse(false, e.message ?: "Failed to static analysis.")
    }
}
