package com.walter.spring.ai.ops.connector.dto

data class GithubDifferInquiry(
    val owner: String,
    val repo: String,
    val base: String,
    val head: String,
)
