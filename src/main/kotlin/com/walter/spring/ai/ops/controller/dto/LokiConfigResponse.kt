package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.lang.Exception

@Schema(description = "Response for Loki URL configuration save operation")
data class LokiConfigResponse private constructor(
    @Schema(description = "Result status (OK / ERROR)", example = "OK")
    val status: String,
    @Schema(description = "Error message; empty on success")
    val message: String = "",
) {
    companion object {
        fun success(): LokiConfigResponse = LokiConfigResponse("OK")
        fun failure(e: Exception): LokiConfigResponse = LokiConfigResponse("ERROR", e.message ?: "Unknown error.")
    }
}
