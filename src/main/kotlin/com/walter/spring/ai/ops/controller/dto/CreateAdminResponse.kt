package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Create admin account result")
data class CreateAdminResponse(
    @Schema(description = "Whether the account creation succeeded")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String,
) {
    companion object {
        fun success() = CreateAdminResponse(true, "Admin account created successfully.")
        fun failure(e: Exception) = CreateAdminResponse(false, e.message ?: "Failed to create admin account.")
    }
}