package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class GithubUrlStatusResponse(
    @JsonProperty("isConfigured")
    val isConfigured: Boolean,
    val githubUrl: String = "",
)
