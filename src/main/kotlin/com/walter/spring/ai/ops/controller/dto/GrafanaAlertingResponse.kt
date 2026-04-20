package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.code.AlertingStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for a Grafana Alerting webhook")
data class GrafanaAlertingResponse(
    @Schema(description = "Processing status (FIRING / RESOLVED)", example = "FIRING")
    val status: String,
    @Schema(description = "Human-readable description of the processing result")
    val message: String,
) {
    companion object {
        fun of(alertingStatus: AlertingStatus): GrafanaAlertingResponse {
            return GrafanaAlertingResponse(
                status = alertingStatus.name,
                message = alertingStatus.description,
            )
        }
    }
}
