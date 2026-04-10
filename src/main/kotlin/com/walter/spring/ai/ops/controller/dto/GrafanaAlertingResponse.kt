package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.code.AlertingStatus

data class GrafanaAlertingResponse(
    val status: String,
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
