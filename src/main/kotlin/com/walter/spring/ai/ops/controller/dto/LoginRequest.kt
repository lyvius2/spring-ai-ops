package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Login request")
data class LoginRequest(
    @Schema(description = "Admin username", example = "admin")
    val username: String,
    @Schema(description = "Admin password")
    val password: String,
)