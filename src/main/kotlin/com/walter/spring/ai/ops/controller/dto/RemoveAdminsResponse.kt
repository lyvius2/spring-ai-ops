package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Remove admin accounts result")
data class RemoveAdminsResponse(
    @Schema(description = "Whether the removal succeeded")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String,
) {
    companion object {
        fun success() = RemoveAdminsResponse(true, "Selected accounts have been removed.")
        fun failure(e: Exception) = RemoveAdminsResponse(false, e.message ?: "Failed to remove accounts.")
    }
}