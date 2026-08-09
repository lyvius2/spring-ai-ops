package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubReviewResponse(
    val id: Long = 0L,
    @JsonProperty("node_id") val nodeId: String = "",
    val body: String = "",
    val state: String = "",
    @JsonProperty("html_url") val htmlUrl: String = "",
    @JsonProperty("pull_request_url") val pullRequestUrl: String = "",
    @JsonProperty("commit_id") val commitId: String = "",
    @JsonProperty("submitted_at") val submittedAt: String = "",
    val errorMessage: String? = null,
)