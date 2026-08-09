package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for a GitHub/GitLab pull request webhook")
data class GithubPullRequestResponse(
    @Schema(description = "Processing status of the pull request event", example = "ACCEPTED")
    val status: String,
) {
    companion object {
        fun accepted(): GithubPullRequestResponse = GithubPullRequestResponse("ACCEPTED")
        fun skipped(): GithubPullRequestResponse = GithubPullRequestResponse("SKIPPED")
    }
}
