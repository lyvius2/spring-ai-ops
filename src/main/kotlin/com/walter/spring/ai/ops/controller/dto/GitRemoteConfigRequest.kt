package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request body for saving Git remote provider configuration")
data class GitRemoteConfigRequest(
    @Schema(description = "Git remote provider (GITHUB / GITLAB)", example = "GITHUB", required = true)
    val provider: String = "",
    @Schema(description = "Personal access token for the Git provider", example = "ghp_...", required = true)
    val token: String = "",
    @Schema(description = "Base URL of the Git provider (leave empty for GitHub.com / GitLab.com)", example = "https://gitlab.example.com")
    val url: String = "",
)
