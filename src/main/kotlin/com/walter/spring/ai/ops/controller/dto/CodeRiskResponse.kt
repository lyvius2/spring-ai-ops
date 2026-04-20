package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.record.CodeRiskRecord
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for a code risk analysis request")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeRiskResponse(
    @Schema(description = "Whether the analysis completed successfully")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String,
    @Schema(description = "Analysis result record; present only on success", nullable = true)
    val data: CodeRiskRecord? = null,
) {
    companion object {
        fun success(data: CodeRiskRecord) = CodeRiskResponse(true, "Static analysis completed successfully.", data)
        fun failure(e: Exception) = CodeRiskResponse(false, extractMessage(e.message))

        private fun extractMessage(raw: String?): String {
            if (raw == null) return "Static analysis failed."
            return runCatching {
                val jsonStart = raw.indexOf('{')
                val json = if (jsonStart >= 0) raw.substring(jsonStart) else raw
                val node = ObjectMapper().readTree(json)
                node.path("error").path("message").asText().takeIf { it.isNotBlank() }
                    ?: node.path("message").asText().takeIf { it.isNotBlank() }
                    ?: raw
            }.getOrDefault(raw)
        }
    }
}
