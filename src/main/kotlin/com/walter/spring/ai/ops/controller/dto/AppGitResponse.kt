package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Application with its linked Git repository URL")
data class AppGitResponse(
    @Schema(description = "Application name")
    val name: String,
    @Schema(description = "Linked Git repository URL", nullable = true)
    val gitUrl: String?
)