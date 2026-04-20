package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for Git remote configuration save operation")
data class GitRemoteConfigResponse(
    @Schema(description = "Whether the configuration was saved successfully")
    val success: Boolean,
    @Schema(description = "Human-readable result message")
    val message: String = "",
) {
    companion object {
        fun success() = GitRemoteConfigResponse(success = true)
        fun failure(message: String) = GitRemoteConfigResponse(success = false, message = message)
    }
}
