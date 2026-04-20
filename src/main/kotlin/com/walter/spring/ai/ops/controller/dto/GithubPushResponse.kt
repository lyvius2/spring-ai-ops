package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for a GitHub/GitLab push webhook")
data class GithubPushResponse(
    @Schema(description = "Processing status of the push event", example = "ACCEPTED")
    val status: String,
) {
    companion object {
        fun accepted(): GithubPushResponse {
            return GithubPushResponse("ACCEPTED")
        }
    }
}
