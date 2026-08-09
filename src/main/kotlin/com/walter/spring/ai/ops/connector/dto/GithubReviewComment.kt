package com.walter.spring.ai.ops.connector.dto

data class GithubReviewComment(
    val path: String,
    val line: Int,
    val side: String,
    val body: String,
)