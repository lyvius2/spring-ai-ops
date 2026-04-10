package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.walter.spring.ai.ops.code.ConnectionStatus

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiConfigResponse(
    val status: String,
    val message: String,
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
