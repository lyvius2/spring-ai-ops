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
) {
    companion object {
        fun success() = CodeRiskResponse(true, "Code risk analysis has been started.")
        fun failure(e: Exception) = CodeRiskResponse(false, extractMessage(e.message))

        private fun extractMessage(raw: String?): String {
            if (raw == null) return "Code risk analysis failed."
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
