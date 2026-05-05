package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Change password request")
data class ChangePasswordRequest(
    @Schema(description = "Current password")
    val currentPassword: String,
    @Schema(description = "New password")
    val newPassword: String,
    @Schema(description = "New password confirmation — must match newPassword")
    val confirmPassword: String,
)