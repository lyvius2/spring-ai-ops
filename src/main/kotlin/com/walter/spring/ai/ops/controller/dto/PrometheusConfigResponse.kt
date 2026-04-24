package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.lang.Exception

@Schema(description = "Response for Prometheus URL configuration save operation")
data class PrometheusConfigResponse private constructor(
    @Schema(description = "Result status (OK / ERROR)", example = "OK")
    val status: String,
    @Schema(description = "Error message; empty on success")
    val message: String = "",
) {
    companion object {
        fun success(): PrometheusConfigResponse = PrometheusConfigResponse("OK")
        fun failure(e: Exception): PrometheusConfigResponse = PrometheusConfigResponse("ERROR", e.message ?: "Unknown error.")
    }
}
