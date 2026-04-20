package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for application create/update operation")
data class AppUpdateResponse(
    @Schema(description = "Whether the operation was successful")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String
) {
    companion object {
        fun success() = AppUpdateResponse(true, "Application updated successfully.")
        fun failure(e: Exception) = AppUpdateResponse(false, e.message ?: "Failed to update application.")
    }
}