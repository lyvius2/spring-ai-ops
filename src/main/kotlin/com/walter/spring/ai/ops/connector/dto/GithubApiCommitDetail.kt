package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubApiCommitDetail(
    val message: String = "",
    val author: GithubApiCommitAuthor = GithubApiCommitAuthor(),
)

