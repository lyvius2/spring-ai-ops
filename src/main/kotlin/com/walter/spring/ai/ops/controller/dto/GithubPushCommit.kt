package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Individual commit entry within a GitHub/GitLab push webhook payload")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushCommit(
    @Schema(description = "Commit SHA", example = "abc1234")
    val id: String = "",
    @Schema(description = "Commit message")
    val message: String = "",
    @Schema(description = "URL to the commit on the remote provider")
    val url: String = "",
    @Schema(description = "Commit timestamp in ISO-8601 format", example = "2024-01-01T00:00:00Z")
    val timestamp: String = "",
)
