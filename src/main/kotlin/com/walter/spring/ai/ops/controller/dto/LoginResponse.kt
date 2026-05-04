package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Login / logout result")
data class LoginResponse(
    @Schema(description = "Whether the operation succeeded")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String,
) {
    companion object {
        fun loginSuccess() = LoginResponse(true, "Login successful.")
        fun loginFailure() = LoginResponse(false, "Invalid username or password.")
        fun logoutSuccess() = LoginResponse(true, "Logged out successfully.")
    }
}