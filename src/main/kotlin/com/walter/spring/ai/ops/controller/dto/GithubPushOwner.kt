package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Repository owner within a GitHub/GitLab push webhook payload")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushOwner(
    @Schema(description = "Owner login name (GitHub username or GitLab namespace)", example = "octocat")
    val login: String = "",
)
