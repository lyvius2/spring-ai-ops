package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class GithubReviewRequest(
    @JsonProperty("commit_id") val commitId: String,
    val body: String,
    val event: String = "COMMENT",
    val comments: List<GithubReviewComment> = emptyList(),
)