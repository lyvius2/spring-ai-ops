package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubFile(
    val filename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String = "",
)
