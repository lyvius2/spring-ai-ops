package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Create admin account request")
data class CreateAdminRequest(
    @Schema(description = "New admin username")
    val username: String,
    @Schema(description = "Password")
    val password: String,
    @Schema(description = "Password confirmation — must match password")
    val confirmPassword: String,
)