package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Admin account list")
data class AdminListResponse(
    @Schema(description = "List of admin accounts")
    val admins: List<AdminInfo>,
)