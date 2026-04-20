package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Repository information within a GitHub/GitLab push webhook payload")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushRepository(
    @Schema(description = "Repository name", example = "my-repo")
    val name: String = "",
    @Schema(description = "Repository owner")
    val owner: GithubPushOwner = GithubPushOwner(),
    @Schema(description = "HTML URL of the repository", example = "https://github.com/octocat/my-repo")
    @JsonProperty("html_url") val htmlUrl: String = "",
)
