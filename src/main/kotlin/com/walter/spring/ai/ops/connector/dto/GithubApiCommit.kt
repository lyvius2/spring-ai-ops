package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubApiCommit(
    val sha: String = "",
    @JsonProperty("html_url") val htmlUrl: String = "",
    val commit: GithubApiCommitDetail = GithubApiCommitDetail(),
)

