package com.walter.spring.ai.ops.controller.dto

import com.walter.spring.ai.ops.service.dto.AppConfig
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Application with its linked Git repository and deploy branch")
data class AppGitResponse(
    @Schema(description = "Application name")
    val name: String,
    @Schema(description = "Linked Git repository URL", nullable = true)
    val gitUrl: String?,
    @Schema(description = "Deploy branch — the branch deployed to production", nullable = true)
    val deployBranch: String?,
) {
    companion object {
        @JvmStatic
        fun of(name: String, config: AppConfig?): AppGitResponse {
            return AppGitResponse(name, config?.gitUrl, config?.deployBranch)
        }
    }
}