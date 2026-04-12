package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushCommit(
    val id: String = "",
    val message: String = "",
    val url: String = "",
    val timestamp: String = "",
)
