package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Change password result")
data class ChangePasswordResponse(
    @Schema(description = "Whether the password change succeeded")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String,
) {
    companion object {
        fun changePasswordSuccess() = ChangePasswordResponse(true, "Password changed successfully.")
        fun changePasswordFailure(e: Exception) = ChangePasswordResponse(false, e.message ?: "Password change failed.")
    }
}