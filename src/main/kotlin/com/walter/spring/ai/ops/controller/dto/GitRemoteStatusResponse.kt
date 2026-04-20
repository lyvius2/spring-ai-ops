package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Configuration status for both GitHub and GitLab providers")
data class GitRemoteStatusResponse(
    @Schema(description = "Whether a GitHub personal access token is configured")
    val githubTokenConfigured: Boolean,
    @Schema(description = "Whether a GitHub base URL property is configured")
    val githubPropertyConfigured: Boolean,
    @Schema(description = "Whether a GitLab personal access token is configured")
    val gitlabTokenConfigured: Boolean,
    @Schema(description = "Whether a GitLab base URL property is configured")
    val gitlabPropertyConfigured: Boolean,
    @Schema(description = "Configured GitHub base URL", example = "https://api.github.com")
    val githubUrl: String,
    @Schema(description = "Configured GitLab base URL", example = "https://gitlab.com")
    val gitlabUrl: String,
) {
    companion object {
        fun of(configMap: Map<String, Any?>): GitRemoteStatusResponse {
            return GitRemoteStatusResponse(
                githubTokenConfigured = configMap["githubTokenConfigured"] as? Boolean ?: false,
                githubPropertyConfigured = configMap["githubPropertyConfigured"] as? Boolean ?: false,
                gitlabTokenConfigured = configMap["gitlabTokenConfigured"] as? Boolean ?: false,
                gitlabPropertyConfigured = configMap["gitlabPropertyConfigured"] as? Boolean ?: false,
                githubUrl = configMap["githubUrl"] as? String ?: "",
                gitlabUrl = configMap["gitlabUrl"] as? String ?: "",
            )
        }
    }
}
