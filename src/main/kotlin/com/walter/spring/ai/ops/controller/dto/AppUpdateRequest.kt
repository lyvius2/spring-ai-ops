package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for creating or updating an application")
data class AppUpdateRequest(
    @Schema(description = "Application name", example = "my-service", required = true)
    val name: String,
    @Schema(description = "Git repository URL to link (optional, HTTP/HTTPS only)", example = "https://github.com/org/repo", nullable = true)
    val gitUrl: String? = null,
    @Schema(description = "Deploy branch — the branch deployed to production (optional). Requires gitUrl.", example = "main", nullable = true)
    val deployBranch: String? = null,
)
