package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.walter.spring.ai.ops.code.ConnectionStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for LLM configuration save operation")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiConfigResponse(
    @Schema(description = "Connection status (SUCCESS / READY / FAILURE)", example = "SUCCESS")
    val status: String,
    @Schema(description = "Human-readable result message")
    val message: String,
    @Schema(description = "LLM provider display name returned on success", nullable = true)
    val llm: String?
) {
    companion object {
        fun of(connectionStatus: ConnectionStatus, llm: String? = null): AiConfigResponse {
            return AiConfigResponse(
                status = connectionStatus.name,
                message = connectionStatus.message + (llm ?: ""),
                llm = llm
            )
        }
        fun error(message: String?): AiConfigResponse {
            return AiConfigResponse(
                status = ConnectionStatus.FAILURE.name,
                message = message ?: ConnectionStatus.FAILURE.message,
                llm = null
            )
        }
    }
}
