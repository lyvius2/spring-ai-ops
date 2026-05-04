package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "Admin account info")
data class AdminInfo(
    @Schema(description = "Username")
    val username: String,
    @Schema(description = "Account creation time")
    val createdAt: Instant?,
    @Schema(description = "Most recent login time")
    val lastLoginAt: Instant?,
)